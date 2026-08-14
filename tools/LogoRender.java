import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * car.obj / wheel.obj を焼いて Mod 一覧のロゴ（{@code mods.toml} の {@code logoFile}）を作る。
 * 出力は {@code src/main/resources/kurumamod.png}。CurseForge のプロジェクトアバターにも
 * そのまま使える（400×400 推奨だが、512 を上げれば先方で縮む）。
 *
 * <p>依存は無い。リポジトリのルートで:</p>
 *
 * <pre>
 * javac -encoding UTF-8 -d build/tools tools/LogoRender.java
 * java  -cp build/tools LogoRender
 * java  -cp build/tools LogoRender examples/carpack/examplepack.png "" Kuruma "Car Pack"
 * </pre>
 *
 * <p>引数は {@code [出力先] [モデルの置き場] [ワードマーク] [副題]}。空文字なら既定のまま。
 * カーパックの見本のロゴは、本体と同じ車・同じ色に副題だけを足して作っている
 * （見た目で本体の仲間だと分かるように）。</p>
 *
 * <p><b>配置は {@code CarObjRenderer} と同じものを再現している</b>——荷重配分ぶんの軸中点の
 * ずれ・静止時のサス長・タイヤ半径。諸元は {@code CarSpec.DEFAULT} の写しなので、
 * <b>既定値を変えたらここの定数も直すこと</b>（{@code tools/blender_gauge.py} と同じ約束）。</p>
 *
 * <p>俯瞰を 8.5 度と浅くしてあるのは、モデルにガラスも内装も無いため。上から覗くと
 * <b>窓の穴から向こう側のタイヤやテールランプが見えてしまう</b>ので、角度で避けている。</p>
 */
public class LogoRender {

    /** モデルの置き場。既定はリポジトリのルートから実行した前提。第 2 引数で差し替えられる */
    static String DIR = "src/main/resources/assets/kurumamod/models/entity/";

    // ---- CarSpec.DEFAULT から ----
    static final double WHEEL_BASE = 3.12;
    static final double TRACK_WIDTH = 1.80;
    static final double WHEEL_RADIUS = 0.45;
    static final double WEIGHT_BIAS = 0.56;
    static final double SUS_MAX = 0.25;
    static final double MASS = 1200.0, G = 9.81;
    static final double K_FRONT = 33600.0, K_REAR = 26400.0;

    static double staticSus(boolean front) {
        double load = MASS * G * (front ? WEIGHT_BIAS : 1 - WEIGHT_BIAS) / 2.0;
        return SUS_MAX - load / (front ? K_FRONT : K_REAR);
    }

    static final double RIDE_HEIGHT = (staticSus(true) + staticSus(false)) / 2.0 + WHEEL_RADIUS;
    static final double AXLE_MIDPOINT = -((0.5 - WEIGHT_BIAS) * WHEEL_BASE); // エンティティ空間は符号が逆

    // ---- 三角形 ----
    static final class Tri {
        double[][] p = new double[3][];   // ワールド座標
        float[] col;
        boolean emissive;
        double[] n;                        // 面法線
    }

    // ---- OBJ 読み込み（ObjModel と同じく X と Z を反転、gauge_ は捨てる）----
    static Map<String, List<double[][]>> loadObj(String file) throws Exception {
        List<double[]> pos = new ArrayList<>();
        Map<String, List<double[][]>> out = new LinkedHashMap<>();
        String group = "";
        for (String line : Files.readAllLines(Paths.get(DIR + file), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "v" -> pos.add(new double[]{-Double.parseDouble(t[1]), Double.parseDouble(t[2]), -Double.parseDouble(t[3])});
                case "o", "g" -> group = line.substring(2).trim();
                case "f" -> {
                    if (group.startsWith("gauge_")) break;
                    int n = t.length - 1;
                    double[][] poly = new double[n][];
                    for (int i = 0; i < n; i++) {
                        int idx = Integer.parseInt(t[i + 1].split("/")[0]);
                        poly[i] = pos.get(idx > 0 ? idx - 1 : pos.size() + idx);
                    }
                    List<double[][]> list = out.computeIfAbsent(group, k -> new ArrayList<>());
                    for (int i = 1; i + 1 < n; i++) list.add(new double[][]{poly[0], poly[i], poly[i + 1]});
                }
                default -> { }
            }
        }
        return out;
    }

    // ---- ベクトル ----
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return l < 1e-12 ? new double[]{0, 1, 0} : new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    static float[] rgb(int hex) {
        return new float[]{((hex >> 16) & 255) / 255f, ((hex >> 8) & 255) / 255f, (hex & 255) / 255f};
    }

    // ================= シーン組み立て =================
    static List<Tri> buildScene(int bodyColor, double steerDeg) throws Exception {
        List<Tri> tris = new ArrayList<>();

        Map<String, List<double[][]>> car = loadObj("car.obj");
        for (Map.Entry<String, List<double[][]>> e : car.entrySet()) {
            String name = e.getKey();
            float[] col;
            boolean emissive = false;
            if (name.equals("light_head")) { col = rgb(0xFFF6D8); emissive = true; }
            else if (name.equals("light_tail")) { col = rgb(0xFF2A2A); emissive = true; }
            else col = rgb(bodyColor);

            for (double[][] tri : e.getValue()) {
                Tri t = new Tri();
                t.col = col;
                t.emissive = emissive;
                for (int i = 0; i < 3; i++) {
                    double[] v = tri[i];
                    // CarObjRenderer#renderBody: translate(0, -designRideHeight, +axleMidpoint)
                    // そのあと地面が y=0 になるよう全体を RIDE_HEIGHT だけ持ち上げる
                    t.p[i] = new double[]{v[0], v[1], v[2] + AXLE_MIDPOINT};
                }
                tris.add(t);
            }
        }

        Map<String, List<double[][]>> wheel = loadObj("wheel.obj");
        // タイヤ本体か（車軸からの距離で判定）
        double maxR = 0;
        for (List<double[][]> l : wheel.values())
            for (double[][] tri : l)
                for (double[] v : tri) maxR = Math.max(maxR, Math.hypot(v[1], v[2]));

        for (boolean left : new boolean[]{true, false}) {
            for (boolean front : new boolean[]{true, false}) {
                double x = (left ? -0.5 : 0.5) * TRACK_WIDTH;
                double fwd = (front ? 1 - WEIGHT_BIAS : -WEIGHT_BIAS) * WHEEL_BASE;
                double y = -staticSus(front) + RIDE_HEIGHT;
                double z = -fwd;
                double steer = front ? Math.toRadians(steerDeg) : 0;

                for (List<double[][]> l : wheel.values()) {
                    for (double[][] tri : l) {
                        Tri t = new Tri();
                        for (int i = 0; i < 3; i++) {
                            double[] v = tri[i].clone();
                            double r = Math.hypot(v[1], v[2]);
                            if (t.col == null) {
                                // 外周はタイヤ（黒ゴム）、内側はホイール（銀）
                                t.col = r > maxR * 0.78 ? rgb(0x1B1B1F) : rgb(0xB9BEC6);
                            }
                            if (!left) v[0] = -v[0];                    // 右側は鏡像
                            double sx = v[0] * Math.cos(steer) + v[2] * Math.sin(steer); // Y 軸まわり
                            double sz = -v[0] * Math.sin(steer) + v[2] * Math.cos(steer);
                            t.p[i] = new double[]{sx + x, v[1] + y, sz + z};
                        }
                        tris.add(t);
                    }
                }
            }
        }

        for (Tri t : tris) t.n = norm(cross(sub(t.p[1], t.p[0]), sub(t.p[2], t.p[0])));
        return tris;
    }

    /** 焼いた絵と、接地影を置くための足元の範囲（画像座標）。 */
    record Shot(BufferedImage img, Rectangle2D footprint) { }

    // ================= ラスタライズ =================
    static Shot renderCar(List<Tri> tris, int size, int ss) {
        int W = size * ss;
        int[] px = new int[W * W];
        double[] zb = new double[W * W];
        Arrays.fill(zb, Double.MAX_VALUE);

        // 全体の外接から注視点と距離を決める
        double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
        for (Tri t : tris) for (double[] v : t.p) for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(lo[i], v[i]); hi[i] = Math.max(hi[i], v[i]);
        }
        double[] target = {(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2 + 0.05, (lo[2] + hi[2]) / 2};
        double radius = 0;
        for (Tri t : tris) for (double[] v : t.p) radius = Math.max(radius, Math.sqrt(dot(sub(v, target), sub(v, target))));

        // 俯瞰を浅くする。上から覗くと窓の穴から向こう側のタイヤが見える
        //（ガラスも内装も無いモデルなので、角度で避けるしかない）
        double az = Math.toRadians(38), el = Math.toRadians(8.5);
        double fov = Math.toRadians(26);
        double dist = radius / Math.sin(fov / 2) * 0.86;
        double[] cam = {
                target[0] + dist * Math.sin(az) * Math.cos(el),
                target[1] + dist * Math.sin(el),
                target[2] - dist * Math.cos(az) * Math.cos(el)};

        double[] fw = norm(sub(target, cam));
        double[] rt = norm(cross(fw, new double[]{0, 1, 0}));
        double[] up = cross(rt, fw);

        // 画面いっぱいに収まるよう、投影してから拡大率と中心を決める。
        // 距離で合わせると外接球で決まるぶん必ず余白が出るし、切れるときは切れる
        double uLo = 1e9, uHi = -1e9, vLo = 1e9, vHi = -1e9;
        for (Tri t : tris) for (double[] p : t.p) {
            double[] d = sub(p, cam);
            double vz = dot(d, fw);
            if (vz < 0.01) continue;
            double u = dot(d, rt) / vz, v = dot(d, up) / vz;
            uLo = Math.min(uLo, u); uHi = Math.max(uHi, u);
            vLo = Math.min(vLo, v); vHi = Math.max(vHi, v);
        }
        double margin = 0.045;
        double f = W * (1 - 2 * margin) / Math.max(uHi - uLo, vHi - vLo);
        double cU = (uLo + uHi) / 2, cV = (vLo + vHi) / 2;

        // 足元（タイヤ接地点）の投影。接地影をここへ置く
        Rectangle2D foot = null;
        for (double[] wp : new double[][]{
                {-0.9, 0, -1.3728}, {0.9, 0, -1.3728}, {-0.9, 0, 1.7472}, {0.9, 0, 1.7472}}) {
            double[] d = sub(wp, cam);
            double vz = dot(d, fw);
            double sx = (W / 2.0 + f * (dot(d, rt) / vz - cU)) / ss;
            double sy = (W / 2.0 - f * (dot(d, up) / vz - cV)) / ss;
            Rectangle2D r = new Rectangle2D.Double(sx, sy, 0, 0);
            foot = foot == null ? r : foot.createUnion(r);
        }

        double[] key = norm(new double[]{0.55, 0.78, -0.5});   // 前上方から
        double[] fill = norm(new double[]{-0.7, 0.25, 0.3});
        double[] rim = norm(new double[]{-0.35, 0.45, 0.85});  // 後ろから縁を出す

        for (Tri t : tris) {
            double[][] s = new double[3][];
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                double[] d = sub(t.p[i], cam);
                double vx = dot(d, rt), vy = dot(d, up), vz = dot(d, fw);
                if (vz < 0.01) { ok = false; break; }
                s[i] = new double[]{W / 2.0 + f * (vx / vz - cU), W / 2.0 - f * (vy / vz - cV), vz};
            }
            if (!ok) continue;

            double[] n = t.n.clone();
            double[] toCam = norm(sub(cam, t.p[0]));
            if (dot(n, toCam) < 0) n = new double[]{-n[0], -n[1], -n[2]};

            float[] c;
            if (t.emissive) {
                c = new float[]{Math.min(1, t.col[0] * 1.25f), Math.min(1, t.col[1] * 1.25f), Math.min(1, t.col[2] * 1.25f)};
            } else {
                double kd = Math.max(0, dot(n, key));
                double fd = Math.max(0, dot(n, fill));
                double rd = Math.pow(Math.max(0, dot(n, rim)), 2.5);
                // 上を向く面は空の反射で明るく
                double sky = 0.5 + 0.5 * n[1];
                double lit = 0.16 + 0.28 * sky + 0.72 * kd + 0.22 * fd;
                // 疑似スペキュラ
                double[] h = norm(new double[]{key[0] + toCam[0], key[1] + toCam[1], key[2] + toCam[2]});
                double spec = Math.pow(Math.max(0, dot(n, h)), 42) * 0.85;
                c = new float[3];
                for (int i = 0; i < 3; i++)
                    c[i] = (float) Math.min(1.0, t.col[i] * lit + spec + rd * 0.30);
            }
            int argb = 0xFF000000
                    | ((int) (Math.pow(c[0], 1 / 1.02) * 255) << 16)
                    | ((int) (Math.pow(c[1], 1 / 1.02) * 255) << 8)
                    | (int) (Math.pow(c[2], 1 / 1.02) * 255);

            fill(px, zb, W, s, argb);
        }

        BufferedImage full = new BufferedImage(W, W, BufferedImage.TYPE_INT_ARGB);
        full.setRGB(0, 0, W, W, px, 0, W);

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(full.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return new Shot(img, foot);
    }

    static void fill(int[] px, double[] zb, int W, double[][] s, int argb) {
        int minX = (int) Math.max(0, Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0]))));
        int maxX = (int) Math.min(W - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1]))));
        int maxY = (int) Math.min(W - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
        double area = (s[1][0] - s[0][0]) * (s[2][1] - s[0][1]) - (s[2][0] - s[0][0]) * (s[1][1] - s[0][1]);
        if (Math.abs(area) < 1e-9) return;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double cx = x + 0.5, cy = y + 0.5;
                double w0 = ((s[1][0] - cx) * (s[2][1] - cy) - (s[2][0] - cx) * (s[1][1] - cy)) / area;
                double w1 = ((s[2][0] - cx) * (s[0][1] - cy) - (s[0][0] - cx) * (s[2][1] - cy)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                double z = w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2];
                int i = y * W + x;
                if (z < zb[i]) { zb[i] = z; px[i] = argb; }
            }
        }
    }

    // ================= 仕上げ =================
    static BufferedImage compose(Shot shot, int size, int bg1, int bg2, String text, String sub, String kanji) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 背景。上を暗く、床にあたる下側を明るくする（スタジオのホリゾント）。
        // 真っ黒にすると黒い接地影が見えず、車が浮いて見える
        g.setPaint(new GradientPaint(0, 0, new Color(bg1), 0, size, new Color(bg2)));
        g.fillRect(0, 0, size, size);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(size * 0.46f, size * 0.62f), size * 0.70f,
                new float[]{0f, 1f}, new Color[]{new Color(255, 255, 255, 40), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, size, size);
        // 四隅を落として中央へ目を集める
        g.setPaint(new RadialGradientPaint(new Point2D.Float(size / 2f, size / 2f), size * 0.78f,
                new float[]{0.45f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 120)}));
        g.fillRect(0, 0, size, size);

        // 車。テキストを入れるときは上へ寄せて縮める
        double s = text != null ? 0.90 : 1.0;
        int cw = (int) (size * s);
        int cx = (size - cw) / 2;
        int cy = text != null ? (int) (-size * (sub != null ? 0.075 : 0.045)) : 0;

        // 接地影。タイヤの接地点を投影した範囲へ、車と同じ拡大・移動を掛けて置く
        Rectangle2D fp = shot.footprint();
        double fx = cx + fp.getX() * s, fy = cy + fp.getY() * s;
        double fw = fp.getWidth() * s, fh = fp.getHeight() * s;
        double padX = fw * 0.10, padY = Math.max(fh * 0.55, size * 0.022);
        // 円のぼかしを作ってから楕円へ引き伸ばす。RadialGradientPaint は円形なので、
        // 平たい楕円へそのまま塗ると上下だけ切り落とされる
        int blurN = 256;
        BufferedImage blur = new BufferedImage(blurN, blurN, BufferedImage.TYPE_INT_ARGB);
        Graphics2D bg = blur.createGraphics();
        bg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        bg.setPaint(new RadialGradientPaint(new Point2D.Float(blurN / 2f, blurN / 2f), blurN / 2f,
                new float[]{0f, 0.38f, 1f},
                new Color[]{new Color(0, 0, 0, 235), new Color(0, 0, 0, 150), new Color(0, 0, 0, 0)}));
        bg.fillRect(0, 0, blurN, blurN);
        bg.dispose();
        g.drawImage(blur, (int) (fx - padX), (int) (fy - padY),
                (int) (fw + padX * 2), (int) (fh + padY * 2), null);

        g.drawImage(shot.img(), cx, cy, cw, cw, null);

        if (text != null) {
            Font tf = pickFont(new String[]{"Bahnschrift", "Segoe UI Semibold", "Arial", "SansSerif"},
                    Font.BOLD, (int) (size * 0.152));
            g.setFont(tf);
            FontMetrics fm = g.getFontMetrics();
            String upper = text.toUpperCase();
            int w = fm.stringWidth(upper) + (int) (size * 0.012 * (upper.length() - 1));
            int x = (size - w) / 2, y = (int) (size * (sub != null ? 0.845 : 0.885));
            g.setColor(new Color(0, 0, 0, 120));
            drawTracked(g, upper, x, y + 2, size * 0.012f);
            g.setColor(Color.WHITE);
            drawTracked(g, upper, x, y, size * 0.012f);

            // アクセントの下線
            g.setColor(new Color(0xE8, 0x3B, 0x3B));
            g.fillRect((int) (size * 0.5 - size * 0.085), (int) (size * (sub != null ? 0.885 : 0.925)),
                    (int) (size * 0.17), Math.max(2, size / 170));

            // 副題（カーパックなど、本体との区別）。ワードマークより小さく、字間を広く取る
            if (sub != null) {
                Font sf = pickFont(new String[]{"Bahnschrift", "Segoe UI Semibold", "Arial", "SansSerif"},
                        Font.BOLD, (int) (size * 0.062));
                g.setFont(sf);
                FontMetrics sm = g.getFontMetrics();
                String su = sub.toUpperCase();
                float strack = size * 0.030f;
                int sw = sm.stringWidth(su) + (int) (strack * (su.length() - 1));
                int sx = (size - sw) / 2, sy = (int) (size * 0.965);
                g.setColor(new Color(0, 0, 0, 120));
                drawTracked(g, su, sx, sy + 2, strack);
                g.setColor(new Color(255, 255, 255, 205));
                drawTracked(g, su, sx, sy, strack);
            }
        }

        // 「車」の角バッジ。透かしとして敷くと車体の裏に回って読めなくなるので、
        // 前に出して塗りで置く
        if (kanji != null) {
            int m = (int) (size * 0.055), b = (int) (size * 0.19);
            g.setColor(new Color(0xD4, 0x23, 0x2B));
            g.fill(new RoundRectangle2D.Double(m, m, b, b, b * 0.28, b * 0.28));
            Font kf = pickFont(new String[]{"Yu Gothic", "Meiryo", "MS Gothic", "Noto Sans JP", "SansSerif"},
                    Font.BOLD, (int) (b * 0.74));
            // 字面の外接で中央へ置く。フォントによって余白の付き方が違うので、
            // 文字送り幅ではなく実際の輪郭で測る
            Shape glyph = kf.createGlyphVector(g.getFontRenderContext(), kanji).getOutline();
            Rectangle2D gb = glyph.getBounds2D();
            g.setColor(Color.WHITE);
            g.translate(m + b / 2.0 - gb.getCenterX(), m + b / 2.0 - gb.getCenterY());
            g.fill(glyph);
            g.setTransform(new java.awt.geom.AffineTransform());
        }

        g.dispose();
        return img;
    }

    static void drawTracked(Graphics2D g, String s, int x, int y, float track) {
        FontMetrics fm = g.getFontMetrics();
        for (char c : s.toCharArray()) {
            g.drawString(String.valueOf(c), x, y);
            x += fm.charWidth(c) + track;
        }
    }

    static Font pickFont(String[] names, int style, int size) {
        Set<String> have = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : names) if (have.contains(n)) return new Font(n, style, size);
        return new Font(Font.SANS_SERIF, style, size);
    }

    /** 車体の色。ロゴは青 ＋ ワードマーク */
    static final int BODY_COLOR = 0x1F4FA8;
    /** 背景。上を暗く、床にあたる下を明るく */
    static final int BG_TOP = 0x11141B, BG_FLOOR = 0x39404F;

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "src/main/resources/kurumamod.png";
        if (args.length > 1 && !args[1].isEmpty()) DIR = args[1];
        String text = args.length > 2 && !args[2].isEmpty() ? args[2] : "Kuruma";
        String sub = args.length > 3 && !args[3].isEmpty() ? args[3] : null;
        Path path = Paths.get(out);
        if (path.getParent() != null) Files.createDirectories(path.getParent());

        int size = 512;
        int ss = 3;   // スーパーサンプリング。低ポリなので輪郭のジャギーが目立つ

        System.out.printf("車高 %.4fm / 軸中点ずれ %.4fm / サス 前%.4f 後%.4f%n",
                RIDE_HEIGHT, AXLE_MIDPOINT, staticSus(true), staticSus(false));

        // 前輪を少し切っておく。真っ直ぐだと止まっている車に見える
        Shot car = renderCar(buildScene(BODY_COLOR, 14), size, ss);
        write(compose(car, size, BG_TOP, BG_FLOOR, text, sub, null), out);
    }

    static void write(BufferedImage img, String path) throws Exception {
        ImageIO.write(img, "PNG", new File(path));
        System.out.println("wrote " + path);
    }
}
