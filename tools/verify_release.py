#!/usr/bin/env python3
"""配布用の jar を、製品版のサーバーとクライアントで起動して確かめる。

    python tools/verify_release.py                 全部（6 種 + Sodium・Iris 入りの Fabric 2 種）
    python tools/verify_release.py --build         先に jar を全部作り直す
    python tools/verify_release.py --only fabric   名前に fabric が入る組み合わせだけ
    python tools/verify_release.py --no-iris       Sodium・Iris 入りを飛ばす
    python tools/verify_release.py --jobs 2        同時に動かす数（既定は空きメモリから決める）

組み合わせごとに、新しいスーパーフラットの製品版サーバーを立て、CurseForge のランチャーの
ライブラリから製品版のクライアントを起動して接続する。カーパックの全車種を出し、既定の車に
乗り、画面を撮る。全部通れば終了コード 0、1 つでも落ちれば 1。

開発用の runClient と違い build/libs の jar そのものを読むので、難読化し直した名前
（SRG・intermediary）、カーパックの読み込み、専用サーバーでの読み込み、接続を確かめられる。

置き場所は runs/verify/（.gitignore 済み）。
    report/summary.txt   結果の一覧
    report/sheet.png     全組み合わせの画面（Pillow があるときだけ）
    report/<組み合わせ>/  ログ・画面

要るもの: Windows、CurseForge のランチャーで入れた Forge と NeoForge のクライアント
（~/curseforge/minecraft/Install。別の場所なら --install）。サーバーのインストーラー・Fabric・
Fabric API・Sodium・Iris は初回に公式の配布元から取得して再利用する。
サーバーを動かすために eula.txt を eula=true で書くので、Minecraft の EULA に同意している前提。
"""
import argparse
import concurrent.futures
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "runs" / "verify"
HOME = Path.home()
PLAYER = "Kuruma_Test"
BASE_PORT = 25600
AGENT = "kurumamod-verify"

SERVER_XMX = "1G"
CLIENT_XMX = "1500M"
# 並列の数を決めるときの、1 組みあたりの空きメモリの目安 [MB]。1 組みはサーバーとクライアントで
# 2.5GB まで使えるが、起動の山は揃わない（空き 3GB で 2 並列が動いた）
CASE_MB = 1500
# 全部 C1 で動かす。起動と短い確認だけなので、最適化に使う時間のほうが惜しい
FAST_JVM = ["-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"]

JOIN_TIMEOUT = 420
# 接続を始めてから入れるまでの待ち [s]。ふだんは 2 秒で入る。
# Forge 1.20.1 でときどき、サーバーが握手のパケットを送ったのにクライアントが 1 つも受け取らないまま
# 止まる（サーバーは 30 秒で切るが、クライアントは気づかずに何分も待つ）。そのときは状態を残して起動し直す
CONNECT_TIMEOUT = 45
SERVER_TIMEOUT = 400
RENDER_SETTLE = 8   # 接続してからチャンクが見えるまで
SHOT_SETTLE = 5     # 車を出してから、乗ってから画面を撮るまで

SHOT_PS1 = r'''param([int]$ProcId,[string]$Out)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;using System.Runtime.InteropServices;
public class W{[DllImport("user32.dll")]public static extern bool PrintWindow(IntPtr h,IntPtr dc,uint f);
[DllImport("user32.dll")]public static extern bool GetWindowRect(IntPtr h,out R r);
public struct R{public int L,T,Rt,B;}}
"@
$h=(Get-Process -Id $ProcId).MainWindowHandle
if($h -eq 0){exit 1}
$r=New-Object W+R;[W]::GetWindowRect($h,[ref]$r)|Out-Null
$b=New-Object Drawing.Bitmap ($r.Rt-$r.L),($r.B-$r.T)
$g=[Drawing.Graphics]::FromImage($b);$dc=$g.GetHdc()
[W]::PrintWindow($h,$dc,2)|Out-Null;$g.ReleaseHdc($dc)
$b.Save($Out,[Drawing.Imaging.ImageFormat]::Png)
'''


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ---- 取得 -----------------------------------------------------------------------------------

def http_get(url, dest=None):
    req = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if dest is None:
        return data
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    tmp.write_bytes(data)
    tmp.replace(dest)
    return dest


def cached(url, name):
    dest = WORK / "downloads" / name
    if not dest.exists():
        log(f"取得: {url}")
        http_get(url, dest)
    return dest


def modrinth_file(project, game, loader, version=None):
    q = urllib.parse.urlencode({"game_versions": json.dumps([game]), "loaders": json.dumps([loader])})
    versions = json.loads(http_get(f"https://api.modrinth.com/v2/project/{project}/version?{q}"))
    hits = [v for v in versions if version is None or v["version_number"] == version]
    # 版を決めていないときは正式版を優先する（無ければベータ）
    hits = [v for v in hits if v["version_type"] == "release"] or hits
    if not hits:
        raise SystemExit(f"Modrinth に {project} {version or ''}（{game} {loader}）が無い")
    f = next((x for x in hits[0]["files"] if x.get("primary")), hits[0]["files"][0])
    return cached(f["url"], f"{project}-{game}/{f['filename']}")


# ---- 環境 -----------------------------------------------------------------------------------

def properties(path):
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def find_java(major):
    roots = [Path(p) for p in glob.glob("C:/Program Files/Java/*")] + [Path(p) for p in glob.glob(str(HOME / ".gradle/jdks/*"))]
    for r in roots:
        if re.search(rf"(^|[-_]){major}([-_.]|$)", r.name) and (r / "bin/java.exe").exists():
            return r / "bin/java.exe"
    raise SystemExit(f"JDK {major} が見つからない")


def available_mb():
    out = subprocess.run(["powershell.exe", "-NoProfile", "-Command",
                          "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],
                         capture_output=True, text=True).stdout
    return int(out.strip()) // 1024


def car_packs():
    """carpack/* の今の版の jar と、その中の車種。"""
    jars, cars = [], []
    for d in sorted((REPO / "carpack").iterdir()):
        meta = d / "fabric.mod.json"
        if not meta.exists():
            continue
        version = json.loads(meta.read_text(encoding="utf-8"))["version"]
        hits = [Path(p) for p in glob.glob(str(d / f"*{version}.jar"))]
        if not hits:
            raise SystemExit(f"{d.name} の {version} の jar が無い")
        jars.append(hits[0])
        for n in zipfile.ZipFile(hits[0]).namelist():
            m = re.fullmatch(r"data/([^/]+)/cars/([^/]+)\.json", n)
            if m:
                cars.append(f"{m[1]}:{m[2]}")
    return jars, cars


class Env:
    def __init__(self, args):
        self.install = Path(args.install)
        self.mod_version = properties(REPO / "gradle.properties")["mod_version"]
        self.packs, self.cars = car_packs()
        self.java = {n: find_java(n) for n in (17, 21, 25)}
        self.fab = {"1.21.1": properties(REPO / "versions/1.21.1/fabric/gradle.properties"),
                    "26.3": properties(REPO / "versions/26.3/fabric/gradle.properties")}
        self.forge = {"1.20.1": properties(REPO / "versions/1.20.1/forge/gradle.properties")["forge_version"],
                      "26.3": properties(REPO / "versions/26.3/forge/gradle.properties")["forge_version"]}
        self.neo = {"1.21.1": properties(REPO / "versions/1.21.1/neoforge/gradle.properties")["neo_version"],
                    "26.3": properties(REPO / "versions/26.3/neoforge/gradle.properties")["neo_version"]}


class Case:
    def __init__(self, env, mc, loader, iris=False):
        self.env, self.mc, self.loader, self.iris = env, mc, loader, iris
        self.name = f"{loader}-{mc}" + ("-iris" if iris else "")
        self.java = env.java[{"1.20.1": 17, "1.21.1": 21, "26.3": 25}[mc]]
        self.jar = REPO / "build/libs" / f"kurumamod-{loader}-{mc}-{env.mod_version}.jar"
        self.server = WORK / "servers" / self.name
        self.client = WORK / "clients" / self.name
        self.report = WORK / "report" / self.name
        self.cl_mods = []
        self.mods = []


def client_version(case):
    """このローダーのクライアントの版。Forge はランチャーに同じ版が無ければ同じ世代の最新で代える。"""
    env = case.env
    if case.loader == "fabric":
        return f"fabric-loader-{env.fab[case.mc]['loader_version']}-{case.mc}"
    versions = env.install / "versions"
    if case.loader == "neoforge":
        v = f"neoforge-{env.neo[case.mc]}"
        if not (versions / v).exists():
            raise SystemExit(f"{v} のクライアントが無い（CurseForge のランチャーで入れること）")
        return v
    want = env.forge[case.mc]
    if (versions / f"forge-{want}").exists():
        return f"forge-{want}"
    major = want.split(".")[0]
    hits = sorted((p.name for p in versions.glob(f"forge-{major}.*")),
                  key=lambda s: [int(x) for x in s[6:].split(".")])
    if not hits:
        raise SystemExit(f"forge-{major}.* のクライアントが無い（CurseForge のランチャーで入れること）")
    log(f"{case.name}: クライアントは forge-{want} が無いので {hits[-1]} で代える（サーバーは {want}）")
    return hits[-1]


# ---- サーバーの用意 ---------------------------------------------------------------------------

def prepare_server(case):
    d = case.server
    if case.loader == "fabric":
        loader = case.env.fab[case.mc]["loader_version"]
        installers = json.loads(http_get("https://meta.fabricmc.net/v2/versions/installer"))
        jar = cached(f"https://meta.fabricmc.net/v2/versions/loader/{case.mc}/{loader}/{installers[0]['version']}/server/jar",
                     f"fabric-server-{case.mc}-{loader}.jar")
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy(jar, d / "fabric-server-launch.jar")
        return
    if glob.glob(str(d / "libraries/net/*/*/*/win_args.txt")):
        return
    if case.loader == "forge":
        v = f"{case.mc}-{case.env.forge[case.mc]}"
        url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{v}/forge-{v}-installer.jar"
    else:
        v = case.env.neo[case.mc]
        url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{v}/neoforge-{v}-installer.jar"
    installer = cached(url, f"{case.loader}-{v}-installer.jar")
    d.mkdir(parents=True, exist_ok=True)
    log(f"{case.name}: サーバーをインストール")
    with open(d / "install.log", "wb") as out:
        subprocess.run([str(case.java), "-jar", str(installer), "--installServer", "."], cwd=d, stdout=out,
                       stderr=subprocess.STDOUT, check=True)


def prepare_mods(case):
    case.mods = [case.jar] + case.env.packs
    if case.loader == "fabric":
        case.mods.append(modrinth_file("fabric-api", case.mc, "fabric", case.env.fab[case.mc]["fabric_api_version"]))
    if case.iris:
        case.cl_mods = [modrinth_file(p, case.mc, "fabric") for p in ("sodium", "iris")]


# ---- クライアントの起動の引数 --------------------------------------------------------------------

def rules_ok(rules):
    if not rules:
        return True
    allow = False
    for r in rules:
        if "features" in r:
            return False
        o = r.get("os", {})
        if o.get("name") in (None, "windows") and o.get("arch") in (None, "x86_64"):
            allow = r["action"] == "allow"
    return allow


def lib_path(lib):
    p = (lib.get("downloads") or {}).get("artifact", {}).get("path")
    if p:
        return p
    name, *ext = lib["name"].split("@")
    group, artifact, version, *classifier = name.split(":")
    suffix = f"-{classifier[0]}" if classifier else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{suffix}.{ext[0] if ext else 'jar'}"


def lib_key(lib):
    parts = lib["name"].split("@")[0].split(":")
    return ":".join(parts[:2] + parts[3:])


def version_json(env, version):
    local = env.install / "versions" / version / f"{version}.json"
    if local.exists():
        return json.loads(local.read_text(encoding="utf-8"))
    m = re.fullmatch(r"fabric-loader-(.+?)-(.+)", version)
    if not m:
        raise SystemExit(f"{version} の定義が無い")
    return json.loads(http_get(f"https://meta.fabricmc.net/v2/versions/loader/{m[2]}/{m[1]}/profile/json"))


def client_command(case):
    env = case.env
    chain, v = [], client_version(case)
    while v:
        j = version_json(env, v)
        chain.append(j)
        v = j.get("inheritsFrom")
    libs, order = {}, []
    for j in chain:   # 継承先の定義を優先する
        for lib in j.get("libraries", []):
            if rules_ok(lib.get("rules")) and lib_key(lib) not in libs:
                libs[lib_key(lib)] = lib
                order.append(lib)
    classpath = []
    for lib in order:
        if "downloads" in lib and not lib["downloads"].get("artifact"):
            continue
        rel = lib_path(lib)
        path = env.install / "libraries" / rel
        if not path.exists():
            path = WORK / "libraries" / rel
            if not path.exists():
                if not lib.get("url"):
                    raise SystemExit(f"{rel} が無い")
                log(f"取得: {rel}")
                http_get(lib["url"].rstrip("/") + "/" + rel, path)
        classpath.append(str(path))
    # Forge・NeoForge は自分の名前の jar（ignoreList で除外される）を渡す。素の jar を渡すと
    # minecraft のモジュールが 2 つになって起動しない
    base, top = chain[-1], chain[0]
    own = env.install / "versions" / top["id"] / f"{top['id']}.jar"
    classpath.append(str(own if own.exists() else env.install / "versions" / base["id"] / f"{base['id']}.jar"))

    values = {"library_directory": str(env.install / "libraries"), "classpath_separator": ";", "version_name": top["id"],
              "natives_directory": str(case.client / "natives"), "launcher_name": "verify", "launcher_version": "1",
              "classpath": ";".join(classpath), "auth_player_name": PLAYER, "version_type": "release",
              "game_directory": str(case.client), "assets_root": str(env.install / "assets"),
              "assets_index_name": base["assetIndex"]["id"], "auth_uuid": "0" * 32, "auth_access_token": "0",
              "clientid": "0", "auth_xuid": "0", "user_type": "legacy", "user_properties": "{}"}
    subst = lambda s: re.sub(r"\$\{(\w+)\}", lambda m: values.get(m[1], m[0]), s)

    def arguments(kind):
        out = []
        for j in reversed(chain):
            for a in (j.get("arguments") or {}).get(kind, []):
                if isinstance(a, str):
                    out.append(subst(a))
                elif rules_ok(a.get("rules")):
                    value = a["value"]
                    out += [subst(x) for x in (value if isinstance(value, list) else [value])]
        return out

    return ([str(case.java), f"-Xmx{CLIENT_XMX}", *FAST_JVM] + arguments("jvm") + [top["mainClass"]] + arguments("game")
            + ["--width", "1280", "--height", "720"])


# ---- 1 組みの実行 ----------------------------------------------------------------------------

def wait_for(path, pattern, timeout, proc=None, count=1):
    end = time.time() + timeout
    while time.time() < end:
        try:
            text = Path(path).read_text(encoding="utf-8", errors="replace")
            if len(re.findall(pattern, text)) >= count:
                return True
        except FileNotFoundError:
            pass
        if proc is not None and proc.poll() is not None:
            return False
        time.sleep(0.5)
    return False


def dump_stuck(case, procs):
    """接続で止まったときの、スレッドの状態と接続の一覧を report に残す（次に原因を探すため）。"""
    jcmd = case.java.parent / "jcmd.exe"
    for proc in procs:
        for pid in java_pids(proc.pid):
            if jcmd.exists():
                out = subprocess.run([str(jcmd), str(pid), "Thread.print"], capture_output=True, text=True,
                                     errors="replace")
                (case.report / f"stuck-threads-{pid}.txt").write_text(out.stdout + out.stderr, encoding="utf-8")
    ns = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, errors="replace").stdout
    (case.report / "stuck-netstat.txt").write_text(ns, encoding="utf-8")
    for root, tag in ((case.server, "server"), (case.client, "client")):
        debug = root / "logs/debug.log"
        if debug.exists():
            shutil.copy(debug, case.report / f"stuck-{tag}-debug.log")


def java_pids(pid):
    """pid と、その子のうち java のもの（ランチャーが間に挟まることがある）。"""
    out = subprocess.run(["powershell.exe", "-NoProfile", "-Command",
                          f"Get-CimInstance Win32_Process | Where-Object {{ $_.ProcessId -eq {pid} -or "
                          f"$_.ParentProcessId -eq {pid} }} | Where-Object {{ $_.Name -like 'java*' }} | "
                          "ForEach-Object { $_.ProcessId }"], capture_output=True, text=True)
    return [int(x) for x in out.stdout.split() if x.isdigit()]


def screenshot(proc, out):
    r = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(WORK / "shot.ps1"),
                        "-ProcId", str(proc.pid), "-Out", str(out)], capture_output=True, text=True)
    return r.returncode == 0 and Path(out).exists()


def kill_tree(pid):
    subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)


def clean(root, names):
    for n in names:
        shutil.rmtree(root / n, ignore_errors=True)
    (root / "mods").mkdir(parents=True, exist_ok=True)


def write_settings(case, port):
    (case.server / "eula.txt").write_text("eula=true\n")
    (case.server / "server.properties").write_text(
        f"server-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nspawn-protection=0\n"
        "view-distance=4\nsimulation-distance=4\nlevel-type=minecraft\\:flat\ngamemode=creative\nspawn-monsters=false\n"
        "spawn-animals=false\nenforce-secure-profile=false\nmotd=verify\n")
    options = ("onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:4\nsimulationDistance:5\n"
               "fullscreen:false\nnarrator:0\nsoundCategory_master:0\ntutorialStep:none\nskipMultiplayerWarning:true\n")
    if case.mc == "26.3":
        options += 'preferredGraphicsBackend:"opengl"\n'
    (case.client / "options.txt").write_text(options)


def run_case(case, port, env):
    r = {"case": case.name, "checks": {}, "errors": [], "seconds": 0}
    checks = r["checks"]
    start = time.time()
    shutil.rmtree(case.report, ignore_errors=True)
    case.report.mkdir(parents=True)
    # .fabric の変換済み jar を残すため、ほかだけ消す（1.21.1 の Fabric の起動が速くなる）
    clean(case.server, ("mods", "world", "logs", "crash-reports", "config"))
    clean(case.client, ("mods", "logs", "crash-reports", "config", "saves"))
    for f in case.mods:
        shutil.copy(f, case.server / "mods")
        shutil.copy(f, case.client / "mods")
    for f in case.cl_mods:
        shutil.copy(f, case.client / "mods")
    write_settings(case, port)

    server_out, client_out = case.report / "server.out", case.report / "client.out"
    if case.loader == "fabric":
        cmd = [str(case.java), f"-Xmx{SERVER_XMX}", *FAST_JVM, "-jar", "fabric-server-launch.jar", "nogui"]
    else:
        args = glob.glob(str(case.server / "libraries/net/*/*/*/win_args.txt"))[0]
        cmd = [str(case.java), f"-Xmx{SERVER_XMX}", *FAST_JVM, "@" + os.path.relpath(args, case.server).replace(os.sep, "/"), "nogui"]
    server = subprocess.Popen(cmd, cwd=case.server, stdin=subprocess.PIPE, stdout=open(server_out, "wb"), stderr=subprocess.STDOUT)
    client = None

    def say(line):
        server.stdin.write((line + "\n").encode())
        server.stdin.flush()

    try:
        checks["server"] = wait_for(server_out, r"Done \([\d.,]+s\)", SERVER_TIMEOUT, server)
        if not checks["server"]:
            return r
        for attempt in (1, 2):
            client = subprocess.Popen(client_command(case) + ["--quickPlayMultiplayer", f"localhost:{port}"],
                                      cwd=case.client, stdin=subprocess.DEVNULL,
                                      stdout=open(client_out, "ab" if attempt > 1 else "wb"), stderr=subprocess.STDOUT)
            started_at = time.time()
            # 起動にかかる時間は組み合わせで大きく違うので、待つのは接続を始めてから
            connecting = wait_for(client_out, r"Connecting to ", JOIN_TIMEOUT, client, count=attempt)
            left = JOIN_TIMEOUT - (time.time() - started_at)
            checks["join"] = connecting and wait_for(server_out, PLAYER + " joined the game",
                                                     min(CONNECT_TIMEOUT, max(left, 1)), client)
            if checks["join"] or not connecting or attempt == 2 or client.poll() is not None:
                break
            log(f"{case.name}: 接続を始めて {CONNECT_TIMEOUT} 秒たっても入れない。状態を report に残して、クライアントを起動し直す")
            r["connect_retry"] = True
            dump_stuck(case, (server, client))
            kill_tree(client.pid)
            client.wait(30)
        if not checks["join"]:
            return r
        time.sleep(RENDER_SETTLE)
        for line in ("time set noon", "weather clear", f"tp {PLAYER} 0 -59 0 0 15"):
            say(line)
        # カーパックの車を前に横一列、後ろに既定の車
        for i, car in enumerate(env.cars):
            x = (i - (len(env.cars) - 1) / 2) * 6
            say(f'summon kurumamod:car {x} -60 13 {{CarId:"{car}"}}')
        say('summon kurumamod:car 0 -60 -13 {CarId:"kurumamod:car"}')
        checks["summon"] = wait_for(server_out, "Summoned", 20, count=len(env.cars) + 1)
        time.sleep(SHOT_SETTLE)
        shots = [screenshot(client, case.report / "cars.png")]
        say(f"ride {PLAYER} mount @e[type=kurumamod:car,sort=furthest,limit=1,x=0,y=-60,z=40]")
        checks["ride"] = wait_for(server_out, "started riding", 20)
        time.sleep(SHOT_SETTLE)
        shots.append(screenshot(client, case.report / "ride.png"))
        checks["screenshot"] = all(shots)
        checks["client_alive"] = client.poll() is None
        return r
    except Exception as e:
        r["errors"].append(repr(e))
        return r
    finally:
        if client and client.poll() is None:
            kill_tree(client.pid)
        try:
            say("stop")
            server.wait(30)
        except Exception:
            kill_tree(server.pid)
        finish_report(case, r, env)
        r["seconds"] = round(time.time() - start)
        (case.report / "result.json").write_text(json.dumps(r, ensure_ascii=False, indent=1), encoding="utf-8")


def finish_report(case, r, env):
    texts = []
    for root, tag in ((case.server, "server"), (case.client, "client")):
        latest = root / "logs/latest.log"
        if latest.exists():
            shutil.copy(latest, case.report / f"{tag}-latest.log")
        for f in glob.glob(str(root / "crash-reports/*")):
            shutil.copy(f, case.report)
            r["errors"].append(f"クラッシュレポート: {tag} {os.path.basename(f)}")
    for f in ("server.out", "client.out", "client-latest.log"):
        p = case.report / f
        if p.exists():
            texts.append(p.read_text(encoding="utf-8", errors="replace"))
    text = "\n".join(texts)
    r["checks"]["car_types"] = all(c in text for c in env.cars)
    for line in text.splitlines():
        if re.search(r"Mixin (apply|transformation).*failed|InvalidMixinException|MixinApplyError", line):
            r["errors"].append(line[:200])
        elif re.search(r"kuruma", line, re.I) and re.search(r"\b(ERROR|FATAL)\b|Exception", line):
            r["errors"].append(line[:200])
    r["errors"] = list(dict.fromkeys(r["errors"]))[:12]


def passed(r):
    return bool(r["checks"]) and all(r["checks"].values()) and not r["errors"]


# ---- 全体 -----------------------------------------------------------------------------------

def build(env):
    gradlew = str(REPO / "gradlew.bat")
    jdk = lambda n: dict(os.environ, JAVA_HOME=str(env.java[n].parent.parent))
    log("ビルド（1.20.1・1.21.1）")
    subprocess.run([gradlew, "build", "--console=plain"], cwd=REPO, env=jdk(21), check=True)
    log("ビルド（26.3）")
    subprocess.run([str(REPO / "versions/26.3/gradlew.bat"), "build", "--console=plain"], cwd=REPO / "versions/26.3", env=jdk(25), check=True)


def contact_sheet(results):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        log("Pillow が無いので report/sheet.png は作らない")
        return
    w, h = 480, 281
    columns = 4
    rows = (len(results) + columns - 1) // columns * 2
    sheet = Image.new("RGB", (w * columns, h * rows), "black")
    draw = ImageDraw.Draw(sheet)
    for i, r in enumerate(results):
        x = (i % columns) * w
        for k, name in enumerate(("cars", "ride")):
            y = (i // columns * 2 + k) * h
            p = WORK / "report" / r["case"] / f"{name}.png"
            if p.exists():
                sheet.paste(Image.open(p).convert("RGB").resize((w, h)), (x, y))
            draw.text((x + 6, y + 6), f"{r['case']} {name}", fill="yellow" if passed(r) else "red")
    sheet.save(WORK / "report/sheet.png")


def main():
    ap = argparse.ArgumentParser(description="配布用の jar を製品版のサーバーとクライアントで確かめる")
    ap.add_argument("--build", action="store_true", help="先に jar を全部作り直す")
    ap.add_argument("--only", action="append", default=[], help="名前にこの文字列が入る組み合わせだけ（複数可）")
    ap.add_argument("--no-iris", action="store_true", help="Sodium・Iris 入りを飛ばす")
    ap.add_argument("--jobs", type=int, default=0, help="同時に動かす数（既定は空きメモリから）")
    ap.add_argument("--install", default=str(HOME / "curseforge/minecraft/Install"), help="ランチャーの Install フォルダ")
    args = ap.parse_args()

    env = Env(args)
    if args.build:
        build(env)
    cases = [Case(env, mc, loader) for mc, loader in (("1.20.1", "forge"), ("1.21.1", "neoforge"), ("1.21.1", "fabric"),
                                                      ("26.3", "forge"), ("26.3", "neoforge"), ("26.3", "fabric"))]
    if not args.no_iris:
        cases += [Case(env, "1.21.1", "fabric", iris=True), Case(env, "26.3", "fabric", iris=True)]
    if args.only:
        cases = [c for c in cases if any(s in c.name for s in args.only)]
    if not cases:
        raise SystemExit("組み合わせが無い")
    for c in cases:
        if not c.jar.exists():
            raise SystemExit(f"{c.jar.name} が無い（--build で作る）")

    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "shot.ps1").write_text(SHOT_PS1, encoding="ascii")
    shutil.rmtree(WORK / "report", ignore_errors=True)
    for c in cases:     # 取得とインストールは順番に（同じファイルを同時に落とさない）
        prepare_server(c)
        prepare_mods(c)
        c.client.mkdir(parents=True, exist_ok=True)

    free = available_mb()
    jobs = args.jobs or max(1, min(len(cases), os.cpu_count() // 2, free // CASE_MB))
    log(f"{len(cases)} 組みを {jobs} 並列で実行（空きメモリ {free}MB）。車種: {', '.join(env.cars)}")
    started = time.time()
    ports, lock, results = list(range(BASE_PORT, BASE_PORT + jobs)), threading.Lock(), {}

    def worker(case):
        with lock:
            port = ports.pop()
        try:
            r = run_case(case, port, env)
        finally:
            with lock:
                ports.append(port)
        log(f"{'OK  ' if passed(r) else 'FAIL'} {case.name}（{r['seconds']} 秒）")
        return r

    with concurrent.futures.ThreadPoolExecutor(jobs) as pool:
        for c, r in zip(cases, pool.map(worker, cases)):
            results[c.name] = r
    ordered = [results[c.name] for c in cases]

    lines = [f"KurumaMod {env.mod_version}  {len(cases)} 組み  {jobs} 並列  {round(time.time() - started)} 秒"]
    for r in ordered:
        failed = [k for k, v in r["checks"].items() if not v]
        note = "  （1 回目の接続で止まったので起動し直した。stuck-* を残してある）" if r.get("connect_retry") else ""
        lines.append(f"{'OK  ' if passed(r) else 'FAIL'} {r['case']:<22} {r['seconds']:>4} 秒  {' '.join(failed)}{note}")
        lines += [f"     {e}" for e in r["errors"]]
    summary = "\n".join(lines)
    (WORK / "report/summary.txt").write_text(summary + "\n", encoding="utf-8")
    contact_sheet(ordered)
    print(summary)
    print(f"ログと画面: {WORK / 'report'}")
    return 0 if all(passed(r) for r in ordered) else 1


if __name__ == "__main__":
    sys.exit(main())
