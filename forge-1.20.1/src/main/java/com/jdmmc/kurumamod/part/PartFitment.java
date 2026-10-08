package com.jdmmc.kurumamod.part;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
 */
public final class PartFitment {

    private static final Logger LOGGER = LogUtils.getLogger();

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
    public static final ResourceLocation NONE_PART = ResourceLocation.tryParse("kurumamod:none");

    /** 「なし」の印か。 */
    public static boolean isNone(@Nullable ResourceLocation partId) {
        return NONE_PART.equals(partId);
    }

    /** 何も付けていない状態。<b>＝車種の既定の見た目。</b> */
    public static final PartFitment EMPTY = new PartFitment(new EnumMap<>(PartSlot.class));

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
    public record Setting(@Nullable ResourceLocation partId, @Nullable Double camber,
                          @Nullable Double offset, @Nullable Double width, @Nullable Double angle,
                          @Nullable Double tone) {

        public static final Setting NONE = new Setting(null, null, null, null, null, null);

        /** 音の低さを持たない形。角度までしか無かった頃の呼び出しのため。 */
        public Setting(@Nullable ResourceLocation partId, @Nullable Double camber,
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

    private PartFitment(EnumMap<PartSlot, Setting> parts) {
        this.parts = parts;
    }

    /** その場所の状態。何もしていなければ {@link Setting#NONE}（null は返さない）。 */
    public Setting get(PartSlot slot) {
        Setting setting = parts.get(slot);
        return setting != null ? setting : Setting.NONE;
    }

    /** その場所に付いている部品の id。付いていなければ null（＝車種の既定）。 */
    @Nullable
    public ResourceLocation getPart(PartSlot slot) {
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
        return parts.isEmpty();
    }

    /**
     * 初期装備を足した、<b>実際に付いている状態</b>。描画・諸元・音はこちらを読む。
     *
     * <p>何も指定していない場所には初期装備の id を入れ、「なし」の印は null（何も付いていない）にする。
     * 角度などの調整値はそのまま残す。<b>保存・通信するのは元の状態のほう</b>——初期装備を書き込んで
     * しまうと、カーパックを更新して初期装備を変えても、置いてある車に反映されなくなる。</p>
     */
    public PartFitment resolve(Map<PartSlot, ResourceLocation> defaults) {
        boolean anyNone = parts.values().stream().anyMatch(setting -> isNone(setting.partId()));
        if (defaults.isEmpty() && !anyNone) {
            return this;
        }
        EnumMap<PartSlot, Setting> next = new EnumMap<>(PartSlot.class);
        next.putAll(parts);
        for (PartSlot slot : PartSlot.VALUES) {
            Setting setting = get(slot);
            ResourceLocation id = setting.partId();
            ResourceLocation resolved = isNone(id) ? null : id != null ? id : defaults.get(slot);
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
        return next.isEmpty() ? EMPTY : new PartFitment(next);
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
        // 何も起きず、ログにも何も出ない」。CLAUDE.md の tryRebuildTabContents と同じ形の地雷
        EnumMap<PartSlot, Setting> next = new EnumMap<>(PartSlot.class);
        next.putAll(parts);
        if (setting.isEmpty()) {
            next.remove(slot);
        } else {
            next.put(slot, setting);
        }
        return new PartFitment(next);
    }

    /**
     * 部品だけ入れ替える。<b>キャンバーの上書きは残す。</b>
     *
     * <p>角度は<b>車の設定</b>であって部品の付属物ではないので、履き替えたからといって
     * 勝手に戻さない（戻したければ画面のリセットで部品の既定へ戻せる）。</p>
     *
     * @param partId 付ける部品。<b>null で「外す」</b>（車種の既定に戻る）
     */
    public PartFitment withPart(PartSlot slot, @Nullable ResourceLocation partId) {
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
                camber == null ? null : Mth.clamp(camber, CAMBER_MIN, CAMBER_MAX),
                getOffset(slot), getWidth(slot), getAngle(slot), getTone(slot)));
    }

    /**
     * オフセットだけ入れ替える。
     *
     * @param offset オフセット [mm]。<b>null で「上書きをやめる」</b>（部品ないし車種の指定へ戻る）
     */
    public PartFitment withOffset(PartSlot slot, @Nullable Double offset) {
        return with(slot, new Setting(getPart(slot), getCamber(slot),
                offset == null ? null : Mth.clamp(offset, OFFSET_MIN, OFFSET_MAX),
                getWidth(slot), getAngle(slot), getTone(slot)));
    }

    /**
     * タイヤの太さだけ入れ替える。
     *
     * @param width 太さの倍率。<b>null で「上書きをやめる」</b>（部品ないし車種の指定へ戻る）
     */
    public PartFitment withWidth(PartSlot slot, @Nullable Double width) {
        return with(slot, new Setting(getPart(slot), getCamber(slot), getOffset(slot),
                width == null ? null : Mth.clamp(width, WIDTH_MIN, WIDTH_MAX), getAngle(slot), getTone(slot)));
    }

    /**
     * 空力部品の角度だけ入れ替える。
     *
     * @param angle 範囲の中の位置（0〜1）。<b>null で「上書きをやめる」</b>（部品の指定へ戻る）
     */
    public PartFitment withAngle(PartSlot slot, @Nullable Double angle) {
        return with(slot, new Setting(getPart(slot), getCamber(slot), getOffset(slot), getWidth(slot),
                angle == null ? null : Mth.clamp(angle, ANGLE_MIN, ANGLE_MAX), getTone(slot)));
    }

    /**
     * マフラーの音の低さだけ入れ替える。
     *
     * @param tone 範囲の中の位置（0 で高い、1 で低い）。<b>null で「上書きをやめる」</b>（部品の指定へ戻る）
     */
    public PartFitment withTone(PartSlot slot, @Nullable Double tone) {
        return with(slot, new Setting(getPart(slot), getCamber(slot), getOffset(slot), getWidth(slot),
                getAngle(slot), tone == null ? null : Mth.clamp(tone, TONE_MIN, TONE_MAX)));
    }

    // ------------------------------------------------------------------
    // 保存と通信
    // ------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<PartSlot, Setting> entry : parts.entrySet()) {
            Setting setting = entry.getValue();
            CompoundTag slot = new CompoundTag();
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
        return tag;
    }

    public static PartFitment load(CompoundTag tag) {
        EnumMap<PartSlot, Setting> parts = new EnumMap<>(PartSlot.class);
        for (String key : tag.getAllKeys()) {
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
        return parts.isEmpty() ? EMPTY : new PartFitment(parts);
    }

    /**
     * 1 か所ぶんを読む。
     *
     * <p><b>調整値が無かった頃の形（場所 → id の文字列）も読む。</b>読めるようにしておかないと、
     * それより前に置いた車のホイールが黙って純正へ戻る。</p>
     */
    private static Setting readSetting(CompoundTag tag, String key) {
        if (tag.getTagType(key) == Tag.TAG_STRING) {
            return new Setting(parseId(tag.getString(key), key), null, null, null, null);
        }
        CompoundTag slot = tag.getCompound(key);
        ResourceLocation id = slot.contains(KEY_ID) ? parseId(slot.getString(KEY_ID), key) : null;
        Double camber = slot.contains(KEY_CAMBER)
                ? Mth.clamp(slot.getDouble(KEY_CAMBER), CAMBER_MIN, CAMBER_MAX) : null;
        Double offset = slot.contains(KEY_OFFSET)
                ? Mth.clamp(slot.getDouble(KEY_OFFSET), OFFSET_MIN, OFFSET_MAX) : null;
        Double width = slot.contains(KEY_WIDTH)
                ? Mth.clamp(slot.getDouble(KEY_WIDTH), WIDTH_MIN, WIDTH_MAX) : null;
        Double angle = slot.contains(KEY_ANGLE)
                ? Mth.clamp(slot.getDouble(KEY_ANGLE), ANGLE_MIN, ANGLE_MAX) : null;
        Double tone = slot.contains(KEY_TONE)
                ? Mth.clamp(slot.getDouble(KEY_TONE), TONE_MIN, TONE_MAX) : null;
        return new Setting(id, camber, offset, width, angle, tone);
    }

    @Nullable
    private static ResourceLocation parseId(String raw, String key) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
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
     */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        for (Map.Entry<PartSlot, Setting> entry : parts.entrySet()) {
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
            if (slot == null || !root.get(key).isJsonObject()) {
                continue;
            }
            JsonObject entry = root.getAsJsonObject(key);
            ResourceLocation id = entry.has("id") ? parseId(entry.get("id").getAsString(), key) : null;
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
        return parts.isEmpty() ? EMPTY : new PartFitment(parts);
    }

    @Nullable
    private static Double jsonNumber(JsonObject entry, String key, double min, double max) {
        JsonElement element = entry.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double value = element.getAsDouble();
        return Double.isFinite(value) ? Mth.clamp(value, min, max) : null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(parts.size());
        for (Map.Entry<PartSlot, Setting> entry : parts.entrySet()) {
            buf.writeUtf(entry.getKey().getSerializedName());
            writeSetting(buf, entry.getValue());
        }
    }

    public static PartFitment read(FriendlyByteBuf buf) {
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
        return parts.isEmpty() ? EMPTY : new PartFitment(parts);
    }

    public static void writeSetting(FriendlyByteBuf buf, Setting setting) {
        buf.writeBoolean(setting.partId() != null);
        if (setting.partId() != null) {
            buf.writeResourceLocation(setting.partId());
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

    public static Setting readSetting(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readBoolean() ? buf.readResourceLocation() : null;
        // 送り手は信用できない（クライアント権威なので改造できる）。範囲は受けた側で押し込む
        Double camber = buf.readBoolean()
                ? Mth.clamp(buf.readDouble(), CAMBER_MIN, CAMBER_MAX) : null;
        Double offset = buf.readBoolean()
                ? Mth.clamp(buf.readDouble(), OFFSET_MIN, OFFSET_MAX) : null;
        Double width = buf.readBoolean()
                ? Mth.clamp(buf.readDouble(), WIDTH_MIN, WIDTH_MAX) : null;
        Double angle = buf.readBoolean()
                ? Mth.clamp(buf.readDouble(), ANGLE_MIN, ANGLE_MAX) : null;
        Double tone = buf.readBoolean()
                ? Mth.clamp(buf.readDouble(), TONE_MIN, TONE_MAX) : null;
        return new Setting(id, camber, offset, width, angle, tone);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof PartFitment fitment && parts.equals(fitment.parts));
    }

    @Override
    public int hashCode() {
        return Objects.hash(parts);
    }

    @Override
    public String toString() {
        return "PartFitment" + parts;
    }
}
