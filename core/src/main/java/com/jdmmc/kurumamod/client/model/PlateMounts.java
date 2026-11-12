package com.jdmmc.kurumamod.client.model;

import com.jdmmc.kurumamod.util.Mth;
import com.jdmmc.kurumamod.util.GsonHelper;
import com.jdmmc.kurumamod.car.CarId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jdmmc.kurumamod.plate.PlateLook;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.jdmmc.kurumamod.client.Assets;

/**
 * ナンバーを付ける位置の候補（{@code vehicles/<car>.json} の {@code "plates"}）。<b>どこに付けるかは車が決める。</b>
 *
 * <pre>
 * "plates": {
 *   "front": [
 *     { "id": "center", "pos": [0, 0.42, -2.18] },
 *     { "id": "left",   "pos": [-0.42, 0.38, -2.10], "yaw": -14, "anchor": "left", "scale": 0.9 }
 *   ],
 *   "rear": [ { "id": "center", "pos": [0, 0.78, 2.05], "pitch": -8 } ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code pos} … プレートの原点（取り付け面の中心）を置く位置 [m]。<b>基準は車体メッシュの原点</b>
 *       （前後輪の中点・地面の高さ。座席と同じ）で、+X 右・+Y 上・-Z 前</li>
 *   <li>{@code yaw} … 上から見て時計回りが正 [度]。{@code pitch} … 表が上を向くのが正 [度]。ロールは持たない</li>
 *   <li>{@code scale} … この位置だけの大きさの倍率（既定 1）。プレートの種類ごとの大きさ（{@link PlateModel#scale()}）に掛ける。端を揃えるずれにも掛かる</li>
 *   <li>{@code anchor} … {@code pos} に揃える端。{@code center}（既定）・{@code left}・{@code right}（<b>車から見た左右</b>）。
 *       角に寄せた候補は角の側の端を揃えれば、長いプレートは内側へ伸びる</li>
 *   <li><b>先頭が既定</b>。どれを使うかはプレイヤーが選ぶ（{@link PlateLook#front()}）。知らない id は先頭に読み替える</li>
 *   <li>前の候補を書かなければ前には付かない（書いた側だけに付く）</li>
 * </ul>
 *
 * <p><b>{@code plates} を書いていない車は自動で置く</b>（{@link #automatic}）。配布済みのパックの車にも付くように。</p>
 */
public final class PlateMounts {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PlateMounts.class);

    /** 揃える端。左右は車から見た向き。 */
    public enum Anchor {
        CENTER, LEFT, RIGHT;

        static Anchor byName(@Nullable String name) {
            if ("left".equals(name)) {
                return LEFT;
            }
            return "right".equals(name) ? RIGHT : CENTER;
        }

        public String serializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** 大きさの倍率の範囲。0 や負は描けないので、JSON の値もここへ収める。 */
    public static final float MIN_SCALE = 0.1F;
    public static final float MAX_SCALE = 3.0F;

    /** 候補 1 つ。位置は車体メッシュの原点から [m]、角度は [度]、大きさは倍率。 */
    public record Mount(String id, double x, double y, double z, float pitch, float yaw, float scale, Anchor anchor) {

        public Mount with(double x, double y, double z, float pitch, float yaw, float scale) {
            return new Mount(id, x, y, z, pitch, yaw, scale, anchor);
        }

        public Mount withAnchor(Anchor anchor) {
            return new Mount(id, x, y, z, pitch, yaw, scale, anchor);
        }
    }

    /**
     * 車 1 台ぶんの候補。
     *
     * @param automatic 車の JSON に書かれておらず、車体の形から自動で置いたものか
     */
    public record Mounts(List<Mount> front, List<Mount> rear, boolean automatic) {

        public List<Mount> side(boolean frontSide) {
            return frontSide ? front : rear;
        }

        public Mounts withSide(boolean frontSide, List<Mount> mounts) {
            return frontSide ? new Mounts(List.copyOf(mounts), rear, false) : new Mounts(front, List.copyOf(mounts), false);
        }
    }

    private static final Map<CarId, Mounts> CACHE = new HashMap<>();
    /** 位置の調整画面（Ctrl+G）で動かしている最中の値。ゲームを閉じるまで、この手元だけで効く。 */
    private static final Map<CarId, Mounts> OVERRIDES = new HashMap<>();

    private PlateMounts() {
    }

    public static Mounts get(CarId carId) {
        Mounts override = OVERRIDES.get(carId);
        return override != null ? override : CACHE.computeIfAbsent(carId, PlateMounts::load);
    }

    /** 調整画面の上書きを無視した候補（JSON に書いたもの、無ければ自動で置いたもの）。 */
    public static Mounts base(CarId carId) {
        return CACHE.computeIfAbsent(carId, PlateMounts::load);
    }

    public static void setOverride(CarId carId, Mounts mounts) {
        OVERRIDES.put(carId, mounts);
    }

    public static void clearOverride(CarId carId) {
        OVERRIDES.remove(carId);
    }

    public static boolean hasOverride(CarId carId) {
        return OVERRIDES.containsKey(carId);
    }

    /** リソースの再読み込みで呼ぶ。 */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * 選んだ候補。{@code chosen} が null・知らない id なら先頭。{@link PlateLook#HIDDEN} や候補が無ければ null（付けない）。
     */
    @Nullable
    public static Mount pick(List<Mount> mounts, @Nullable String chosen) {
        if (mounts.isEmpty() || PlateLook.HIDDEN.equals(chosen)) {
            return null;
        }
        if (chosen != null) {
            for (Mount mount : mounts) {
                if (mount.id().equals(chosen)) {
                    return mount;
                }
            }
        }
        return mounts.get(0);
    }

    private static Mounts load(CarId carId) {
        CarId location = CarModel.locationOf(carId);
        Optional<Assets.Asset> resource = Assets.get().getResource(location);
        if (resource.isPresent()) {
            try (BufferedReader reader = resource.get().openAsReader()) {
                JsonObject root = GsonHelper.parse(reader);
                if (root.has("plates") && root.get("plates").isJsonObject()) {
                    JsonObject plates = root.getAsJsonObject("plates");
                    return new Mounts(parseSide(plates, "front", location), parseSide(plates, "rear", location), false);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("ナンバーの位置を読めませんでした（車体の形から自動で置きます）: {}", location, e);
            }
        }
        return automatic(carId);
    }

    private static List<Mount> parseSide(JsonObject plates, String key, CarId location) {
        List<Mount> mounts = new ArrayList<>();
        if (!plates.has(key)) {
            return mounts;
        }
        JsonElement element = plates.get(key);
        // 1 つだけなら配列で囲まなくても読む（書き方は配列が正）
        JsonArray array = element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        if (element.isJsonObject()) {
            array.add(element);
        }
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject json = item.getAsJsonObject();
            String id = GsonHelper.getAsString(json, "id", mounts.isEmpty() ? "center" : "mount" + mounts.size());
            if (!PlateLook.isValidId(id, PlateLook.MOUNT_MAX_LENGTH) || PlateLook.HIDDEN.equals(id)) {
                LOGGER.warn("ナンバーの位置の id は小文字・数字・_ - で、none 以外にしてください（この候補だけ無視します）: {} の {}.{}",
                        location, key, id);
                continue;
            }
            CarModel.Vec3 pos = CarModel.vector(json, "pos", new CarModel.Vec3(0.0, 0.5, key.equals("front") ? -2.0 : 2.0));
            mounts.add(new Mount(id, pos.x(), pos.y(), pos.z(),
                    GsonHelper.getAsFloat(json, "pitch", 0.0F), GsonHelper.getAsFloat(json, "yaw", 0.0F),
                    Mth.clamp(GsonHelper.getAsFloat(json, "scale", 1.0F), MIN_SCALE, MAX_SCALE),
                    Anchor.byName(GsonHelper.getAsString(json, "anchor", null))));
        }
        return List.copyOf(mounts);
    }

    // ------------------------------------------------------------------
    // 自動で置く
    // ------------------------------------------------------------------

    /** 前のナンバーの高さ（車体の高さに対する割合）。当たらなければ順に試す。 */
    private static final double[] FRONT_HEIGHTS = {0.30, 0.25, 0.35, 0.20, 0.40};
    private static final double[] REAR_HEIGHTS = {0.40, 0.35, 0.45, 0.30, 0.50};
    /** 左右に寄せた候補の位置（車幅に対する割合）。 */
    private static final double SIDE_OFFSET = 0.22;
    /** 向きを測るのに、中心から左右へずらす幅 [m]（プレートの半分くらい）。 */
    private static final double TANGENT_SPAN = 0.15;
    /** 表面から浮かせる量 [m]。埋もれてちらつかないように。 */
    private static final double STANDOFF = 0.004;
    /** 自動で付ける傾きの上限 [度]。 */
    private static final float MAX_PITCH = 30.0F;
    private static final float MAX_YAW = 40.0F;

    /**
     * 車体のメッシュから置く。<b>中央の候補は左右に傾けない</b>（バンパーの丸みでナンバーが横を向かないように）。
     * 前は中央・左・右の 3 つ、後ろは中央だけ。
     */
    static Mounts automatic(CarId carId) {
        MeshShape body = MeshShape.get(CarModel.get(carId).bodyModel());
        double[] box = body.bounds();
        if (body.isEmpty()) {
            return new Mounts(List.of(), List.of(), true);
        }
        double width = box[3] - box[0];
        List<Mount> front = new ArrayList<>();
        front.add(place(body, box, "center", 0.0, true, false, Anchor.CENTER));
        front.add(place(body, box, "left", -width * SIDE_OFFSET, true, true, Anchor.CENTER));
        front.add(place(body, box, "right", width * SIDE_OFFSET, true, true, Anchor.CENTER));
        List<Mount> rear = List.of(place(body, box, "center", 0.0, false, false, Anchor.CENTER));
        return new Mounts(List.copyOf(front), rear, true);
    }

    private static Mount place(MeshShape body, double[] box, String id, double x, boolean front,
                               boolean turn, Anchor anchor) {
        double height = box[4] - box[1];
        for (double fraction : front ? FRONT_HEIGHTS : REAR_HEIGHTS) {
            double y = box[1] + height * fraction;
            double[] center = body.surfaceAlongZ(x, y, front);
            if (center == null) {
                continue;
            }
            double z = center[0] + (front ? -STANDOFF : STANDOFF);
            float pitch = (float) Mth.clamp(Math.toDegrees(Math.asin(Mth.clamp(center[2], -1.0, 1.0))),
                    -MAX_PITCH, MAX_PITCH);
            float yaw = 0.0F;
            if (turn) {
                double[] left = body.surfaceAlongZ(x - TANGENT_SPAN, y, front);
                double[] right = body.surfaceAlongZ(x + TANGENT_SPAN, y, front);
                if (left != null && right != null) {
                    // 2 点を結ぶ線に沿わせる。法線（外向き）は線を 90 度回したもの
                    double dx = 2.0 * TANGENT_SPAN;
                    double dz = right[0] - left[0];
                    double nx = front ? dz : -dz;
                    double nz = front ? -dx : dx;
                    double degrees = front ? Math.toDegrees(Math.atan2(nx, -nz)) : Math.toDegrees(Math.atan2(-nx, nz));
                    yaw = (float) Mth.clamp(degrees, -MAX_YAW, MAX_YAW);
                }
            }
            return new Mount(id, x, y, z, pitch, yaw, 1.0F, anchor);
        }
        // どの高さでも当たらない（メッシュが閉じていない・前後が開いている）。外形の箱の面に置く
        LOGGER.warn("ナンバーを置く車体の面が見つかりません（外形の箱の{}に置きます）", front ? "前" : "後ろ");
        double y = box[1] + height * (front ? FRONT_HEIGHTS[0] : REAR_HEIGHTS[0]);
        return new Mount(id, x, y, front ? box[2] - STANDOFF : box[5] + STANDOFF, 0.0F, 0.0F, 1.0F, anchor);
    }
}
