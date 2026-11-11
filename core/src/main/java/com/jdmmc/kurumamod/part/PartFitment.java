package com.jdmmc.kurumamod.part;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.network.RaceBuf;
import com.jdmmc.kurumamod.race.RaceTag;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.plate.PlateLook;
import com.jdmmc.kurumamod.plate.PlateRules;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 車 1 台の装着状態。<b>「どの場所に、どの部品を、どう付けているか」だけを持つ不変の値。</b>
 *
 * <p>車の状態は<b>車種 id・諸元・装着状態</b>の 3 つになった。装着状態も他の 2 つと同じ道を
 * すべて通る必要がある——通っていないところがあると、次のような形で表に出る:</p>
 *
 * <table>
 *   <tr><th>通す先</th><th>通さないとどうなるか</th></tr>
 *   <tr><td>NBT</td><td>リログ・チャンクの読み直しで換装が消える</td></tr>
 *   <tr><td>スポーンデータ</td><td>後から見た人には既定のホイールで見える</td></tr>
 *   <tr><td>{@code CarPartsPacket}</td><td>走行中に換えても他人の画面が変わらない</td></tr>
 *   <tr><td>アイテムの NBT</td><td>シフト＋殴りで回収すると換装が消える</td></tr>
 * </table>
 *
 * <h2>調整値は装着状態の側に持つ</h2>
 *
 * <p>キャンバー角は<b>見た目だけ</b>の値だが、プレイヤーが変えられる以上は
 * <b>車 1 台ごとの状態</b>なので、{@code assets/} ではなくここが持つ。
 * {@code assets/} はクライアント専用でサーバーが読まないため、そちらへ置くと
 * <b>同じ車が人によって違う角度で見える</b>ことになる。</p>
 *
 * <p>書かれていなければ<b>部品（カーパック）が指定した角度</b>、それも無ければ
 * <b>車種の {@code wheel.camber}</b> に落ちる。つまりここにあるのは
 * <b>上書きしたぶんだけ</b>で、上書きを消せばパックの指定へ戻る。</p>
 *
 * <h2>知らない id も覚えておく</h2>
 *
 * <p>{@link CarParts} と突き合わせない。カーパックを外したセーブデータでは見た目が既定へ
 * 落ちるだけで、<b>入れ直せば元に戻る</b>（車種 id とまったく同じ扱い）。</p>
 *
 * <h2>保存も通信も場所の「名前」で書く</h2>
 *
 * <p>序数で書くと、{@link PartSlot} に列挙子を足したときに保存済みの車の装着が
 * 別の場所へずれる。<b>知らない名前は読み飛ばす</b>（将来の版で保存されたデータを
 * 古い版で開いても、その場所のぶんが落ちるだけで済む）。</p>
 *
 * <h2>塗装もここに持つ</h2>
 *
 * <p>どの塗装か（{@link #paint()}）は部品ではないが、<b>プレイヤーが変えられる見た目で、車 1 台ごとの
 * 状態</b>という点でキャンバー角と同じなので、同じ道（NBT・スポーンデータ・{@code CarPartsPacket}・
 * アイテムの NBT）に乗せる。塗装の一覧は {@code vehicles/<car>.json}（クライアントだけが読む）にあるので、
 * <b>サーバーは id を突き合わせずに覚えて配るだけ</b>。知らない id は描く側が既定の色に読み替える。</p>
 */
public final class PartFitment {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PartFitment.class);

    /**
     * キャンバー角の範囲 [度]。<b>画面のスライダーとサーバーの検算が同じ値を見る。</b>
     *
     * <p>片方だけで持つと、改造クライアントが 1000 度のホイールを他人の画面へ出せる。</p>
     */
    public static final double CAMBER_MIN = -30.0;
    public static final double CAMBER_MAX = 10.0;

    /**
     * ホイールオフセットの範囲 [mm]。<b>実車と同じ向きで、小さいほど外へ出る。</b>
     *
     * <p>ET38 の代わりに ET18 を履けばツラが 20mm 出るのと同じ勘定。値そのものは
     * 「この車のこのホイールが、車体の中心からどれだけ外にあるか」を表す絶対値で、
     * 書かれていなければ部品（カーパック）の指定に従う。</p>
     */
    public static final double OFFSET_MIN = -100.0;
    public static final double OFFSET_MAX = 50.0;

    /**
     * タイヤの太さの範囲。<b>メッシュのまま（＝カーパックが作った太さ）が 1.0 の倍率。</b>
     *
     * <p>ミリで持たないのは、<b>何ミリなのかはメッシュ次第</b>だから。倍率にしておけば、
     * 部品が入れ替わっても「その部品の作りに対してどれだけ太いか」の意味が変わらない。</p>
     *
     * <p>太さだけを動かすのは、<b>直径（Y・Z）を触ると接地が崩れる</b>ため——接地点は物理の
     * タイヤ半径から決まっていて、見た目の拡大を物理は知らない。直径を変えたいなら
     * 調整画面のタイヤ半径を使う。</p>
     */
    public static final double WIDTH_MIN = 0.5;
    public static final double WIDTH_MAX = 2.5;

    /**
     * 空力部品の角度の範囲。<b>部品が持つ ClA の範囲（{@code Aero}）の中の位置</b>で、0 で最小、1 で最大。
     *
     * <p>度で持たないのは、<b>何度で何 m² なのかは部品次第</b>だから。位置で持っておけば、
     * 部品を履き替えても「その部品の範囲のどこに置いたか」の意味が変わらない。
     * 角度が変えるのは性能だけで、見た目（車体のメッシュ）は動かない。</p>
     */
    public static final double ANGLE_MIN = 0.0;
    public static final double ANGLE_MAX = 1.0;

    /**
     * マフラーの音の低さの範囲。<b>部品が持つ音程の範囲（{@code CarPartModel.Exhaust}）の中の位置</b>で、
     * 0 でいちばん高く、1 でいちばん低い。音程の倍率で持たないのは、部品ごとに範囲が違うため
     * （角度と同じ考え方）。サーバーは範囲へ丸めて覚えるだけで、音は各クライアントが鳴らす。
     */
    public static final double TONE_MIN = 0.0;
    public static final double TONE_MAX = 1.0;

    /**
     * 「なし」の印。<b>初期装備（{@code default_parts}）のある場所で、それを外したことを表す。</b>
     *
     * <p>何も指定していない（id が null）のは「初期装備のまま」なので、外したことは別の印で持つ。
     * 部品としては存在しない id。{@link #resolve} で null（何も付いていない）に読み替える。</p>
     */
    public static final CarId NONE_PART = CarId.parse("kurumamod:none");

    /** 「なし」の印か。 */
    public static boolean isNone(@Nullable CarId partId) {
        return NONE_PART.equals(partId);
    }

    /** 何も付けていない状態。<b>＝車種の既定の見た目。</b> */
    public static final PartFitment EMPTY = new PartFitment(new EnumMap<>(PartSlot.class), null, PlateLook.EMPTY);

    /**
     * 塗装の id の長さの上限。<b>サーバーは一覧を知らないので、形だけ見て押し込む。</b>
     *
     * <p>使える文字は小文字・数字・{@code _ - .} だけ。改造クライアントが長い文字列を送り付けて、
     * 周りの人の NBT と通信を膨らませることがないように。</p>
     */
    public static final int PAINT_MAX_LENGTH = 64;

    /** 塗装の id として受け付けられる形か。 */
    public static boolean isValidPaint(@Nullable String paint) {
        return paint != null && !paint.isEmpty() && paint.length() <= PAINT_MAX_LENGTH
                && paint.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.');
    }

    /** 受け付けられない id は「指定なし」（＝既定の色）に読み替える。 */
    @Nullable
    private static String sanitizePaint(@Nullable String paint) {
        return isValidPaint(paint) ? paint : null;
    }

    /**
     * NBT の塗装のキー。<b>場所の名前と同じ階層に置く。</b>{@link PartSlot} の名前とはぶつからず、
     * 古い版で開いても「知らない場所」として読み飛ばされる。
     */
    private static final String KEY_PAINT = "paint";

    /**
     * NBT のナンバーの中身のキー。塗装と同じく場所の名前と同じ階層に置く。<b>場所の名前の {@code plate}
     * （どのプレートを付けたか）とは別物</b>なので、名前を分けてある。
     */
    private static final String KEY_PLATE = "plate_look";
    private static final String KEY_PLATE_TEXT = "text";
    private static final String KEY_PLATE_FRONT = "front";
    private static final String KEY_PLATE_REAR = "rear";

    private static final String KEY_ID = "Id";
    private static final String KEY_CAMBER = "Camber";
    private static final String KEY_OFFSET = "Offset";
    private static final String KEY_WIDTH = "Width";
    private static final String KEY_ANGLE = "Angle";
    private static final String KEY_TONE = "Tone";

    /**
     * その場所の状態。
     *
     * <p><b>どちらも null になりうる。</b>部品を履かずに角度だけ変えることも、
     * 履いた部品の角度をそのまま使うこともあるため。両方 null なら「何もしていない」
     * ＝ {@link #isEmpty()} で、そのときは持たない。</p>
     *
     * @param partId 部品の id。null なら車種の既定（＝純正）
     * @param camber キャンバー角 [度] の上書き。null なら部品ないし車種の指定に従う
     * @param offset ホイールオフセット [mm] の上書き。<b>実車と同じ向きで、小さいほど外へ出る。</b>
     *               null なら部品ないし車種の指定に従う
     * @param width  タイヤの太さの倍率の上書き。メッシュのままが 1.0。
     *               null なら部品ないし車種の指定に従う
     * @param angle  空力部品の角度（{@link #ANGLE_MIN}〜{@link #ANGLE_MAX}）の上書き。
     *               null なら部品の指定に従う
     * @param tone   マフラーの音の低さ（{@link #TONE_MIN}〜{@link #TONE_MAX}）の上書き。
     *               null なら部品の指定に従う
     */
    public record Setting(@Nullable CarId partId, @Nullable Double camber,
                          @Nullable Double offset, @Nullable Double width, @Nullable Double angle,
                          @Nullable Double tone) {

        public static final Setting NONE = new Setting(null, null, null, null, null, null);

        /** 音の低さを持たない形。角度までしか無かった頃の呼び出しのため。 */
        public Setting(@Nullable CarId partId, @Nullable Double camber,
                       @Nullable Double offset, @Nullable Double width, @Nullable Double angle) {
            this(partId, camber, offset, width, angle, null);
        }

        public boolean isEmpty() {
            return partId == null && camber == null && offset == null && width == null && angle == null
                    && tone == null;
        }
    }

    /**
     * <b>包まずに {@link EnumMap} のまま持つ。</b>{@code private final} でこのクラスの外へ出ないので
     * 包む必要が無いうえ、包むと {@link #with} が壊れる（下記）。
     */
    private final EnumMap<PartSlot, Setting> parts;
    /** 塗装の id。null なら車種の既定の色。 */
    @Nullable
    private final String paint;
    /** ナンバーの中身（打った字と付ける位置）。null にはしない。 */
    private final PlateLook plate;

    private PartFitment(EnumMap<PartSlot, Setting> parts, @Nullable String paint, PlateLook plate) {
        this.parts = parts;
        this.paint = paint;
        this.plate = plate;
    }

    private static PartFitment of(EnumMap<PartSlot, Setting> parts, @Nullable String paint, PlateLook plate) {
        return parts.isEmpty() && paint == null && plate.isEmpty() ? EMPTY : new PartFitment(parts, paint, plate);
    }

    /**
     * ナンバーの中身（打った字と付ける位置）。どのプレートを付けたかは {@link PartSlot#PLATE} の部品。
     *
     * <p>塗装と同じく「プレイヤーが変えられる見た目で、車 1 台ごとの状態」なので、部品の場所ではなく
     * ここに持つ。様式を替えても残す（欄の名前ごとに持ち越す）。</p>
     */
    public PlateLook plate() {
        return plate;
    }

    /** ナンバーの中身だけ入れ替える。 */
    public PartFitment withPlate(PlateLook plate) {
        EnumMap<PartSlot, Setting> next = new EnumMap<>(PartSlot.class);
        next.putAll(parts);
        return of(next, paint, plate == null ? PlateLook.EMPTY : plate);
    }

    /** 塗装の id（{@code vehicles/<car>.json} の {@code paints[].id}）。null なら車種の既定の色。 */
    @Nullable
    public String paint() {
        return paint;
    }

    /**
     * 塗装だけ入れ替える。
     *
     * @param paint 塗装の id。<b>null で既定の色へ戻す</b>。受け付けられない形の id も null と同じ
     */
    public PartFitment withPaint(@Nullable String paint) {
        EnumMap<PartSlot, Setting> next = new EnumMap<>(PartSlot.class);
        next.putAll(parts);
        return of(next, sanitizePaint(paint), plate);
    }

    /** その場所の状態。何もしていなければ {@link Setting#NONE}（null は返さない）。 */
    public Setting get(PartSlot slot) {
        Setting setting = parts.get(slot);
        return setting != null ? setting : Setting.NONE;
    }

    /** その場所に付いている部品の id。付いていなければ null（＝車種の既定）。 */
    @Nullable
    public CarId getPart(PartSlot slot) {
        return get(slot).partId();
    }

    /** その場所のキャンバー角の上書き。していなければ null（＝部品ないし車種の指定）。 */
    @Nullable
    public Double getCamber(PartSlot slot) {
        return get(slot).camber();
    }

    /** その場所のオフセット [mm] の上書き。していなければ null（＝部品ないし車種の指定）。 */
    @Nullable
    public Double getOffset(PartSlot slot) {
        return get(slot).offset();
    }

    /** その場所の太さの倍率の上書き。していなければ null（＝部品ないし車種の指定）。 */
    @Nullable
    public Double getWidth(PartSlot slot) {
        return get(slot).width();
    }

    /** その場所の空力部品の角度の上書き。していなければ null（＝部品の指定）。 */
    @Nullable
    public Double getAngle(PartSlot slot) {
        return get(slot).angle();
    }

    /** その場所のマフラーの音の低さの上書き。していなければ null（＝部品の指定）。 */
    @Nullable
    public Double getTone(PartSlot slot) {
        return get(slot).tone();
    }

    public boolean isEmpty() {
        return parts.isEmpty() && paint == null && plate.isEmpty();
    }

    /**
     * 初期装備を足した、<b>実際に付いている状態</b>。描画・諸元・音はこちらを読む。
     *
     * <p>何も指定していない場所には初期装備の id を入れ、「なし」の印は null（何も付いていない）にする。
     * 角度などの調整値はそのまま残す。<b>保存・通信するのは元の状態のほう</b>——初期装備を書き込んで
     * しまうと、カーパックを更新して初期装備を変えても、置いてある車に反映されなくなる。</p>
     *
     * <p><b>部品の一覧に無い id（カーパックを外した）は、何も指定していないのと同じに扱う。</b>
     * 初期装備があればそれ、無ければその場所の素の状態（純正・ノーマル・なし）に戻る。こうしないと
     * 外せない初期装備まで消えて、車体に穴が開く。元の状態の id は消さないので、パックを入れ直せば戻る。
     * 一覧が変われば結果も変わるので、呼ぶ側は {@link CarParts#generation()} も見て組み直すこと。</p>
     */
    public PartFitment resolve(Map<PartSlot, CarId> defaults) {
        boolean anyOdd = parts.values().stream()
                .anyMatch(setting -> isNone(setting.partId()) || isUnknown(setting.partId()));
        if (defaults.isEmpty() && !anyOdd) {
            return this;
        }
        EnumMap<PartSlot, Setting> next = new EnumMap<>(PartSlot.class);
        next.putAll(parts);
        for (PartSlot slot : PartSlot.VALUES) {
            Setting setting = get(slot);
            CarId id = setting.partId();
            CarId resolved = isNone(id) ? null : id != null && !isUnknown(id) ? id : defaults.get(slot);
            if (resolved == id) {
                continue;
            }
            Setting changed = new Setting(resolved, setting.camber(), setting.offset(), setting.width(),
                    setting.angle(), setting.tone());
            if (changed.isEmpty()) {
                next.remove(slot);
            } else {
                next.put(slot, changed);
            }
        }
        return of(next, paint, plate);
    }

    /**
     * 部品の一覧に無い id か（カーパックを外した・まだ一覧が届いていない）。「なし」の印は含めない。
     */
    public static boolean isUnknown(@Nullable CarId partId) {
        return partId != null && !isNone(partId) && CarParts.get(partId) == null;
    }

    /** 1 か所だけ入れ替えた装着状態。 */
    public PartFitment with(PartSlot slot, Setting setting) {
        // 型を渡して作ってから putAll すること。
        //
        // new EnumMap<>(map) は、渡されたものが EnumMap でなければ「中身から鍵の型を知る」ので、
        // <b>空のマップを渡すと IllegalArgumentException("Specified map is empty") を投げる</b>。
        // ここは EMPTY（＝何も履いていない車）から呼ばれるのが普通なので、まさにその道を通る。
        //
        // しかも投げ先が悪い——呼び出し元は Forge の enqueueWork の中で、返る CompletableFuture を
        // 誰も見ていないため、<b>例外はログにすら出ずに握り潰される</b>。症状は「換装ボタンを押しても
        // 何も起きず、ログにも何も出ない」。tryRebuildTabContents と同じ形の地雷
        EnumMap<PartSlot, Setting> next = new EnumMap<>(PartSlot.class);
        next.putAll(parts);
        if (setting.isEmpty()) {
            next.remove(slot);
        } else {
            next.put(slot, setting);
        }
        return of(next, paint, plate);
    }

    /**
     * 部品だけ入れ替える。<b>キャンバーの上書きは残す。</b>
     *
     * <p>角度は<b>車の設定</b>であって部品の付属物ではないので、履き替えたからといって
     * 勝手に戻さない（戻したければ画面のリセットで部品の既定へ戻せる）。</p>
     *
     * @param partId 付ける部品。<b>null で「外す」</b>（車種の既定に戻る）
     */
    public PartFitment withPart(PartSlot slot, @Nullable CarId partId) {
        return with(slot, new Setting(partId, getCamber(slot), getOffset(slot), getWidth(slot), getAngle(slot),
                getTone(slot)));
    }

    /**
     * キャンバー角だけ入れ替える。
     *
     * @param camber 角度 [度]。<b>null で「上書きをやめる」</b>（部品ないし車種の指定へ戻る）
     */
    public PartFitment withCamber(PartSlot slot, @Nullable Double camber) {
        return with(slot, new Setting(getPart(slot),
                camber == null ? null : clamp(camber, CAMBER_MIN, CAMBER_MAX),
                getOffset(slot), getWidth(slot), getAngle(slot), getTone(slot)));
    }

    /**
     * オフセットだけ入れ替える。
     *
     * @param offset オフセット [mm]。<b>null で「上書きをやめる」</b>（部品ないし車種の指定へ戻る）
     */
    public PartFitment withOffset(PartSlot slot, @Nullable Double offset) {
        return with(slot, new Setting(getPart(slot), getCamber(slot),
                offset == null ? null : clamp(offset, OFFSET_MIN, OFFSET_MAX),
                getWidth(slot), getAngle(slot), getTone(slot)));
    }

    /**
     * タイヤの太さだけ入れ替える。
     *
     * @param width 太さの倍率。<b>null で「上書きをやめる」</b>（部品ないし車種の指定へ戻る）
     */
    public PartFitment withWidth(PartSlot slot, @Nullable Double width) {
        return with(slot, new Setting(getPart(slot), getCamber(slot), getOffset(slot),
                width == null ? null : clamp(width, WIDTH_MIN, WIDTH_MAX), getAngle(slot), getTone(slot)));
    }

    /**
     * 空力部品の角度だけ入れ替える。
     *
     * @param angle 範囲の中の位置（0〜1）。<b>null で「上書きをやめる」</b>（部品の指定へ戻る）
     */
    public PartFitment withAngle(PartSlot slot, @Nullable Double angle) {
        return with(slot, new Setting(getPart(slot), getCamber(slot), getOffset(slot), getWidth(slot),
                angle == null ? null : clamp(angle, ANGLE_MIN, ANGLE_MAX), getTone(slot)));
    }

    /**
     * マフラーの音の低さだけ入れ替える。
     *
     * @param tone 範囲の中の位置（0 で高い、1 で低い）。<b>null で「上書きをやめる」</b>（部品の指定へ戻る）
     */
    public PartFitment withTone(PartSlot slot, @Nullable Double tone) {
        return with(slot, new Setting(getPart(slot), getCamber(slot), getOffset(slot), getWidth(slot),
                getAngle(slot), tone == null ? null : clamp(tone, TONE_MIN, TONE_MAX)));
    }

    // ------------------------------------------------------------------
    // 保存と通信
    // ------------------------------------------------------------------

    /** NBT（版の {@code PartFitments} が {@code CompoundTag} に包んで渡す）へ書く。 */
    public void save(RaceTag tag) {
        for (Map.Entry<PartSlot, Setting> entry : parts.entrySet()) {
            Setting setting = entry.getValue();
            RaceTag slot = tag.child();
            if (setting.partId() != null) {
                slot.putString(KEY_ID, setting.partId().toString());
            }
            if (setting.camber() != null) {
                slot.putDouble(KEY_CAMBER, setting.camber());
            }
            if (setting.offset() != null) {
                slot.putDouble(KEY_OFFSET, setting.offset());
            }
            if (setting.width() != null) {
                slot.putDouble(KEY_WIDTH, setting.width());
            }
            if (setting.angle() != null) {
                slot.putDouble(KEY_ANGLE, setting.angle());
            }
            if (setting.tone() != null) {
                slot.putDouble(KEY_TONE, setting.tone());
            }
            tag.put(entry.getKey().getSerializedName(), slot);
        }
        if (paint != null) {
            tag.putString(KEY_PAINT, paint);
        }
        if (!plate.isEmpty()) {
            RaceTag plateTag = tag.child();
            savePlate(plateTag, plate);
            tag.put(KEY_PLATE, plateTag);
        }
    }

    private static void savePlate(RaceTag tag, PlateLook plate) {
        if (!plate.text().isEmpty()) {
            RaceTag text = tag.child();
            plate.text().forEach(text::putString);
            tag.put(KEY_PLATE_TEXT, text);
        }
        if (plate.front() != null) {
            tag.putString(KEY_PLATE_FRONT, plate.front());
        }
        if (plate.rear() != null) {
            tag.putString(KEY_PLATE_REAR, plate.rear());
        }
    }

    /** 読めない値は落とす（{@link PlateLook} が形を確かめる）。 */
    private static PlateLook loadPlate(RaceTag tag) {
        Map<String, String> text = new java.util.LinkedHashMap<>();
        RaceTag stored = tag.getCompound(KEY_PLATE_TEXT);
        for (String key : stored.keys()) {
            if (stored.isString(key)) {
                text.put(key, stored.getString(key));
            }
        }
        String front = tag.isString(KEY_PLATE_FRONT) ? tag.getString(KEY_PLATE_FRONT) : null;
        String rear = tag.isString(KEY_PLATE_REAR) ? tag.getString(KEY_PLATE_REAR) : null;
        return new PlateLook(text, front, rear);
    }

    public static PartFitment load(RaceTag tag) {
        EnumMap<PartSlot, Setting> parts = new EnumMap<>(PartSlot.class);
        for (String key : tag.keys()) {
            PartSlot slot = PartSlot.byName(key);
            if (slot == null) {
                // この版が知らない場所。読み飛ばすだけで、他の場所は生きる
                continue;
            }
            Setting setting = readSetting(tag, key);
            if (!setting.isEmpty()) {
                parts.put(slot, setting);
            }
        }
        String paint = tag.isString(KEY_PAINT) ? sanitizePaint(tag.getString(KEY_PAINT)) : null;
        PlateLook plate = tag.isCompound(KEY_PLATE) ? loadPlate(tag.getCompound(KEY_PLATE)) : PlateLook.EMPTY;
        return of(parts, paint, plate);
    }

    /**
     * 1 か所ぶんを読む。
     *
     * <p><b>調整値が無かった頃の形（場所 → id の文字列）も読む。</b>読めるようにしておかないと、
     * それより前に置いた車のホイールが黙って純正へ戻る。</p>
     */
    private static Setting readSetting(RaceTag tag, String key) {
        if (tag.isString(key)) {
            return new Setting(parseId(tag.getString(key), key), null, null, null, null);
        }
        RaceTag slot = tag.getCompound(key);
        CarId id = slot.contains(KEY_ID) ? parseId(slot.getString(KEY_ID), key) : null;
        Double camber = slot.contains(KEY_CAMBER)
                ? clamp(slot.getDouble(KEY_CAMBER), CAMBER_MIN, CAMBER_MAX) : null;
        Double offset = slot.contains(KEY_OFFSET)
                ? clamp(slot.getDouble(KEY_OFFSET), OFFSET_MIN, OFFSET_MAX) : null;
        Double width = slot.contains(KEY_WIDTH)
                ? clamp(slot.getDouble(KEY_WIDTH), WIDTH_MIN, WIDTH_MAX) : null;
        Double angle = slot.contains(KEY_ANGLE)
                ? clamp(slot.getDouble(KEY_ANGLE), ANGLE_MIN, ANGLE_MAX) : null;
        Double tone = slot.contains(KEY_TONE)
                ? clamp(slot.getDouble(KEY_TONE), TONE_MIN, TONE_MAX) : null;
        return new Setting(id, camber, offset, width, angle, tone);
    }

    @Nullable
    private static CarId parseId(String raw, String key) {
        CarId id = CarId.parse(raw);
        if (id == null) {
            LOGGER.warn("部品の id を読めませんでした（この場所だけ外します）: {} = {}", key, raw);
        }
        return id;
    }

    /**
     * プリセット（{@code kurumamod-setups.json} と {@code presets/*.json}）に書く形。
     *
     * <p>NBT と同じく場所の名前で引き、キーも小文字にしてある（手で読み書きされるため）。
     * 何もしていない場所は書かない。</p>
     *
     * <p><b>塗装もナンバーも書かない。</b>プリセットは走りの設定を持ち運ぶもので、読み込むたびに色や
     * ナンバーまで替わると、同じセッティングを別の車で使えなくなる。</p>
     */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        for (Map.Entry<PartSlot, Setting> entry : parts.entrySet()) {
            if (!entry.getKey().inPresets()) {
                continue;
            }
            Setting setting = entry.getValue();
            JsonObject slot = new JsonObject();
            if (setting.partId() != null) {
                slot.addProperty("id", setting.partId().toString());
            }
            if (setting.camber() != null) {
                slot.addProperty("camber", setting.camber());
            }
            if (setting.offset() != null) {
                slot.addProperty("offset", setting.offset());
            }
            if (setting.width() != null) {
                slot.addProperty("width", setting.width());
            }
            if (setting.angle() != null) {
                slot.addProperty("angle", setting.angle());
            }
            if (setting.tone() != null) {
                slot.addProperty("tone", setting.tone());
            }
            root.add(entry.getKey().getSerializedName(), slot);
        }
        return root;
    }

    /** {@link #toJson()} の逆。知らない場所・読めない値は読み飛ばし、範囲へ丸める。 */
    public static PartFitment fromJson(JsonObject root) {
        EnumMap<PartSlot, Setting> parts = new EnumMap<>(PartSlot.class);
        for (String key : root.keySet()) {
            PartSlot slot = PartSlot.byName(key);
            if (slot == null || !slot.inPresets() || !root.get(key).isJsonObject()) {
                continue;
            }
            JsonObject entry = root.getAsJsonObject(key);
            CarId id = entry.has("id") ? parseId(entry.get("id").getAsString(), key) : null;
            Double camber = jsonNumber(entry, "camber", CAMBER_MIN, CAMBER_MAX);
            Double offset = jsonNumber(entry, "offset", OFFSET_MIN, OFFSET_MAX);
            Double width = jsonNumber(entry, "width", WIDTH_MIN, WIDTH_MAX);
            Double angle = jsonNumber(entry, "angle", ANGLE_MIN, ANGLE_MAX);
            Double tone = jsonNumber(entry, "tone", TONE_MIN, TONE_MAX);
            Setting setting = new Setting(id, camber, offset, width, angle, tone);
            if (!setting.isEmpty()) {
                parts.put(slot, setting);
            }
        }
        return of(parts, null, PlateLook.EMPTY);
    }

    @Nullable
    private static Double jsonNumber(JsonObject entry, String key, double min, double max) {
        JsonElement element = entry.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double value = element.getAsDouble();
        return Double.isFinite(value) ? clamp(value, min, max) : null;
    }

    public void write(RaceBuf buf) {
        buf.writeVarInt(parts.size());
        for (Map.Entry<PartSlot, Setting> entry : parts.entrySet()) {
            buf.writeUtf(entry.getKey().getSerializedName());
            writeSetting(buf, entry.getValue());
        }
        buf.writeBoolean(paint != null);
        if (paint != null) {
            buf.writeUtf(paint, PAINT_MAX_LENGTH);
        }
        writePlate(buf, plate);
    }

    public static void writePlate(RaceBuf buf, PlateLook plate) {
        buf.writeVarInt(plate.text().size());
        plate.text().forEach((field, value) -> {
            buf.writeUtf(field, PlateLook.FIELD_ID_MAX_LENGTH);
            buf.writeUtf(value, PlateRules.FIELD_MAX_LENGTH * 2);
        });
        buf.writeUtf(plate.front() == null ? "" : plate.front(), PlateLook.MOUNT_MAX_LENGTH);
        buf.writeUtf(plate.rear() == null ? "" : plate.rear(), PlateLook.MOUNT_MAX_LENGTH);
    }

    /** 送り手は信用できない。数・長さを上限で止め、形の合わないものは {@link PlateLook} が落とす。 */
    public static PlateLook readPlate(RaceBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > PlateRules.MAX_FIELDS) {
            throw buf.invalid("ナンバーの欄が多すぎます: " + count);
        }
        Map<String, String> text = new java.util.LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String field = buf.readUtf(PlateLook.FIELD_ID_MAX_LENGTH);
            text.put(field, buf.readUtf(PlateRules.FIELD_MAX_LENGTH * 2));
        }
        String front = buf.readUtf(PlateLook.MOUNT_MAX_LENGTH);
        String rear = buf.readUtf(PlateLook.MOUNT_MAX_LENGTH);
        return new PlateLook(text, front.isEmpty() ? null : front, rear.isEmpty() ? null : rear);
    }

    public static PartFitment read(RaceBuf buf) {
        int count = buf.readVarInt();
        EnumMap<PartSlot, Setting> parts = new EnumMap<>(PartSlot.class);
        for (int i = 0; i < count; i++) {
            // 知らない場所でも読み飛ばさず必ず最後まで読む。途中で止めると
            // 後続のフィールドがずれて、パケット全体が壊れる
            String name = buf.readUtf();
            Setting setting = readSetting(buf);
            PartSlot slot = PartSlot.byName(name);
            if (slot != null && !setting.isEmpty()) {
                parts.put(slot, setting);
            }
        }
        // 送り手は信用できない。形の合わない id は既定の色に読み替える
        String paint = buf.readBoolean() ? sanitizePaint(buf.readUtf(PAINT_MAX_LENGTH)) : null;
        return of(parts, paint, readPlate(buf));
    }

    public static void writeSetting(RaceBuf buf, Setting setting) {
        buf.writeBoolean(setting.partId() != null);
        if (setting.partId() != null) {
            buf.writeUtf(setting.partId().toString());
        }
        buf.writeBoolean(setting.camber() != null);
        if (setting.camber() != null) {
            buf.writeDouble(setting.camber());
        }
        buf.writeBoolean(setting.offset() != null);
        if (setting.offset() != null) {
            buf.writeDouble(setting.offset());
        }
        buf.writeBoolean(setting.width() != null);
        if (setting.width() != null) {
            buf.writeDouble(setting.width());
        }
        buf.writeBoolean(setting.angle() != null);
        if (setting.angle() != null) {
            buf.writeDouble(setting.angle());
        }
        buf.writeBoolean(setting.tone() != null);
        if (setting.tone() != null) {
            buf.writeDouble(setting.tone());
        }
    }

    public static Setting readSetting(RaceBuf buf) {
        CarId id = null;
        if (buf.readBoolean()) {
            // バニラの readResourceLocation と同じ（文字列。読めない id はパケットごと捨てる）
            String raw = buf.readUtf();
            id = CarId.parse(raw);
            if (id == null) {
                throw buf.invalid("部品の id を読めません: " + raw);
            }
        }
        // 送り手は信用できない（クライアント権威なので改造できる）。範囲は受けた側で押し込む
        Double camber = buf.readBoolean()
                ? clamp(buf.readDouble(), CAMBER_MIN, CAMBER_MAX) : null;
        Double offset = buf.readBoolean()
                ? clamp(buf.readDouble(), OFFSET_MIN, OFFSET_MAX) : null;
        Double width = buf.readBoolean()
                ? clamp(buf.readDouble(), WIDTH_MIN, WIDTH_MAX) : null;
        Double angle = buf.readBoolean()
                ? clamp(buf.readDouble(), ANGLE_MIN, ANGLE_MAX) : null;
        Double tone = buf.readBoolean()
                ? clamp(buf.readDouble(), TONE_MIN, TONE_MAX) : null;
        return new Setting(id, camber, offset, width, angle, tone);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PartFitment fitment && parts.equals(fitment.parts)
                && Objects.equals(paint, fitment.paint) && plate.equals(fitment.plate));
    }

    @Override
    public int hashCode() {
        return Objects.hash(parts, paint, plate);
    }

    @Override
    public String toString() {
        return "PartFitment" + parts + (paint == null ? "" : " paint=" + paint)
                + (plate.isEmpty() ? "" : " plate=" + plate);
    }
}
