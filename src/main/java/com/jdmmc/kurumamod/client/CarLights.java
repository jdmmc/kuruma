package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 灯火の描画。
 *
 * <p>4 つに分かれる:</p>
 * <ul>
 *   <li><b>レンズ</b> … {@code light_*} のオブジェクトを<b>明るさ最大</b>で描く。
 *       消灯時は車体と同じ明るさで、色だけ薄く残す</li>
 *   <li><b>路面の照り返し</b> … 配光（ロービームのカットオフ付き）を<b>実際のブロックの面</b>へ
 *       投影して明るくする（{@link #renderGround}）。<b>Minecraft には動的ライトが無い</b>ので
 *       世界の明るさは変わらないが、見た目の上では路面・段差・壁が照らされる</li>
 *   <li><b>光の筋</b> … ヘッドライトから伸びる薄い光の層（{@link #renderVolume}）</li>
 *   <li><b>光芒</b> … レンズの前に浮かべる光の滲み（{@link #renderGlows}）。
 *       正面から見たときだけ強く出る</li>
 * </ul>
 *
 * <p>どれも<b>周りが暗いときだけ濃くする</b>。昼間に出しっぱなしにすると
 * 白い板が浮いて見えるだけで、ヘッドライトには見えない。</p>
 *
 * <h2>座標</h2>
 *
 * <p>ここで扱う位置はすべて<b>車の補間位置を原点にした世界の向きの座標</b>。
 * レンズの位置と光軸は車体と同じ変換（{@link CarObjRenderer#applyRotations} と
 * {@link CarObjRenderer#applyBodyTransform}）から求めるので、<b>ノーズダイブで照射が手前へ
 * 寄り、スクワットで奥へ伸びる</b>。路面はブロックの座標から原点を引いて置く。</p>
 */
public final class CarLights {

    /** レンズの名前。Blender 側は役割ごとに 1 オブジェクトでよい（左右はミラーのままでよい）。 */
    public static final String HEAD = ObjModel.LIGHT_PREFIX + "head";
    public static final String TAIL = ObjModel.LIGHT_PREFIX + "tail";
    public static final String REVERSE = ObjModel.LIGHT_PREFIX + "reverse";

    /**
     * 点灯時のレンズの色。
     *
     * <p><b>テクスチャがレンズの色を持っていないときだけ</b>掛ける。UV が展開されている
     * レンズには {@link #TEXTURED_COLOR}（白）を掛けて、描かれた色をそのまま出す。</p>
     */
    private static final float[] HEAD_COLOR = {1.0F, 0.97F, 0.88F};
    private static final float[] TAIL_COLOR = {1.0F, 0.15F, 0.10F};
    private static final float[] REVERSE_COLOR = {1.0F, 1.0F, 1.0F};

    /** テクスチャに色が描かれているレンズへ掛ける色。明暗だけを残す。 */
    private static final float[] TEXTURED_COLOR = {1.0F, 1.0F, 1.0F};

    /** 消灯時にレンズへ掛ける暗さ。0 だと真っ黒になって穴に見える。 */
    private static final float OFF_DIM = 0.35F;
    /** テールランプは常時うっすら点いている。ブレーキで最大になる。 */
    private static final float TAIL_IDLE = 0.35F;

    /** 照らされた光の色（ハロゲン寄りの暖かい白）。 */
    private static final float[] BEAM_COLOR = {1.0F, 0.94F, 0.82F};

    // ---- 配光 ----------------------------------------------------------------------
    // 角度はすべて光軸からの<b>正接</b>で持つ（横 = x/z、縦 = y/z）。角度にすると
    // 頂点ごとに atan2 が 2 回要るうえ、小さい角度では正接とほとんど変わらない

    /**
     * 光軸を水平からどれだけ下げるか。
     *
     * <p>実車のロービームは 1% 前後（0.6 度）下向きに合わせる。ここでは 0.9 度で、
     * 車高 60cm なら光軸がおよそ 40m 先で路面に当たる。1.2 度では手前に寄りすぎた。</p>
     */
    private static final double AIM_DOWN = Math.toRadians(0.9);
    /**
     * オートレベライザーの効き。サスの沈み込みで生じた前後の傾きのうち、どれだけ光軸を戻すか。
     *
     * <p>入れないと、ブレーキのノーズダイブで光軸が数度下を向き、<b>止まろうとした瞬間に
     * 前が見えなくなる</b>（加速のスクワットでは逆に空を照らす）。実車にも同じ理由で付いている。
     * 見るのは<b>前後のサスの長さの差</b>だけで、坂による車体の傾きは戻さない——戻すと
     * 上り坂の手前で光が坂を照らさなくなる。1 にしないのは、少しだけ沈んだほうが
     * 「ブレーキを踏んだ」ことが光で分かるから。</p>
     */
    private static final double LEVELING = 0.85;
    /** カットオフ（これより上は照らさない）の、光軸からの高さ。 */
    private static final double CUTOFF = Math.tan(Math.toRadians(0.6));
    /** カットオフのぼかし幅。0 にするとくっきりしすぎて段が見える。 */
    private static final double CUTOFF_SOFT = Math.tan(Math.toRadians(0.5));
    /**
     * 歩道側のカットオフの跳ね上げ（エルボー）。
     *
     * <p>ロービームは対向車を眩惑しないよう道路側を低く抑え、<b>歩道側だけ</b>
     * 15 度の傾きで持ち上げて標識や歩行者を照らす。日本は左側通行なので<b>左が歩道側</b>。</p>
     */
    private static final double KICK_SLOPE = Math.tan(Math.toRadians(15.0));
    private static final double KICK_MAX = Math.tan(Math.toRadians(2.5));
    /** 広がり（横・縦）。路面を広く薄く照らす成分。 */
    private static final double WIDE_X = Math.tan(Math.toRadians(24.0));
    private static final double WIDE_Y = Math.tan(Math.toRadians(8.0));
    private static final double WIDE_WEIGHT = 0.45;
    /** 集光（ホットスポット）。カットオフのすぐ下に集まる明るい芯。 */
    private static final double SPOT_X = Math.tan(Math.toRadians(7.0));
    private static final double SPOT_Y = Math.tan(Math.toRadians(2.2));
    private static final double SPOT_CENTER = -Math.tan(Math.toRadians(0.8));
    private static final double SPOT_WEIGHT = 0.80;

    /**
     * 照度から明るさへの換算。{@code 明るさ = 1 − exp(−(照度 × GAIN)^GAMMA)}。
     *
     * <p>照度は距離の 3 乗で落ちる（距離の 2 乗 × 浅くなる入射角）ので、そのままでは
     * 手前だけ白飛びして奥が真っ暗になる。GAMMA で圧縮して、25m 先でも 4 割ほど残す。</p>
     */
    private static final double EXPOSURE_GAIN = 8600.0;
    private static final double EXPOSURE_GAMMA = 0.6;

    // ---- 路面 ----------------------------------------------------------------------

    /** 路面を照らす奥行き [m]。{@link #GROUND_FADE_START} から先で消えていく。 */
    private static final double GROUND_RANGE = 44.0;
    private static final double GROUND_FADE_START = 30.0;
    /** これより遠い車の路面は描かない。ブロックを数百個調べるので。 */
    private static final double GROUND_MAX_DISTANCE = 96.0;
    /** 下を何ブロックまで探すか。これより深い谷底は照らさない。 */
    private static final int GROUND_SCAN_DEPTH = 6;
    /** 壁を上へ何ブロックまで見るか。光はカットオフより上へ行かないので高くは要らない。 */
    private static final int WALL_SCAN_HEIGHT = 2;
    /** 面から浮かせる量 [m]。z ファイティングよけ。 */
    private static final float SURFACE_LIFT = 0.012F;
    /** 壁の面を縦に刻む幅 [m]。カットオフの線が壁にくっきり出るように。 */
    private static final double WALL_SLICE = 0.25;
    /**
     * 照らされた面を昼の姿へどこまで近づけるか。1 で昼と同じ明るさ。
     *
     * <p>1 にすると照らされた路面だけ真昼になって、夜の景色から浮く。</p>
     */
    private static final float LIT_MAX = 0.82F;
    /** 照らされた面に重ねる光の色味（加算）。これが無いと白昼の色のままで、ライトの暖かさが出ない。 */
    private static final float GROUND_ADD = 0.12F;
    /** テクスチャを持たない面（水面など）は加算だけで描くので、そのぶん強く足す。 */
    private static final float UNTEXTURED_BOOST = 2.5F;
    /** 遮蔽を調べる角度の刻み（光軸の左右 90 度を何分割するか）。 */
    private static final int HORIZON_BINS = 360;

    // ---- 光の筋 --------------------------------------------------------------------

    private static final double VOLUME_LENGTH = 26.0;
    private static final int VOLUME_RINGS = 14;
    private static final int VOLUME_SEGMENTS = 20;
    /** 根元の半径 [m]。レンズより少し大きい。 */
    private static final double VOLUME_START_RADIUS = 0.12;
    /** 横の広がりの半角。配光の広がりより狭い（空気が光って見えるのは芯の近くだけ）。 */
    private static final double VOLUME_SPREAD_X = Math.tan(Math.toRadians(20.0));
    /** 下の縁。上の縁はカットオフ。 */
    private static final double VOLUME_BOTTOM = -Math.tan(Math.toRadians(9.0));
    private static final float VOLUME_ALPHA = 0.16F;

    // ---- 光芒 ----------------------------------------------------------------------

    private static final double HEAD_GLOW_RADIUS = 0.60;
    private static final double TAIL_GLOW_RADIUS = 0.34;
    private static final double BRAKE_GLOW_RADIUS = 0.52;
    private static final double REVERSE_GLOW_RADIUS = 0.30;
    /** 横に流れる光の筋（アナモルフィック風）の半分の長さ [m]。 */
    private static final double STREAK_LENGTH = 1.9;
    private static final double STREAK_HEIGHT = 0.035;
    private static final int GLOW_SEGMENTS = 16;
    /** 光芒の半径方向の濃さ。中心が詰まっていて外へ長く尾を引く。 */
    private static final double[] GLOW_RING_RADIUS = {0.0, 0.16, 0.42, 1.0};
    private static final float[] GLOW_RING_ALPHA = {1.0F, 0.55F, 0.16F, 0.0F};

    /**
     * 明るい場所でも残す濃さの割合。
     *
     * <p>0 にすると昼間はまったく出なくなる。<b>出ていないのか、出ていて見えないのかが
     * 区別できなくなる</b>ので、光の筋と光芒は薄くても常に出す。路面だけは昼間 0 にする
     * （昼の路面を 1 割明るくしても誰にも見えず、ブロックを調べる分だけ損）。</p>
     */
    private static final float VOLUME_DAYLIGHT = 0.20F;
    private static final float GLOW_DAYLIGHT = 0.15F;

    /** 路面の形は 1 ティックに 1 度だけ調べ直す。車ごとに持つ。 */
    private static final Map<CarEntity, GroundField> FIELDS = new WeakHashMap<>();

    private CarLights() {
    }

    /** レンズを描く。点いていれば明るさ最大、消えていれば車体と同じ明るさで暗く。 */
    public static void renderLenses(CarEntity car, ObjModel model, PoseStack pose,
                                    MultiBufferSource buffer, int packedLight,
                                    net.minecraft.resources.ResourceLocation texture) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        lens(model, pose, consumer, packedLight, HEAD, HEAD_COLOR,
                car.isHeadlightOn() ? 1.0F : 0.0F);
        lens(model, pose, consumer, packedLight, TAIL, TAIL_COLOR,
                car.isBrakeLightOn() ? 1.0F : (car.isHeadlightOn() ? TAIL_IDLE : 0.0F));
        lens(model, pose, consumer, packedLight, REVERSE, REVERSE_COLOR,
                car.isReverseLightOn() ? 1.0F : 0.0F);
    }

    /**
     * レンズ 1 つ。
     *
     * <p><b>色を掛けるのは、テクスチャがレンズの色を持っていないときだけ。</b>UV が
     * 1 点に潰れているレンズはテクスチャの 1 画素を全面に写しているだけなので、赤いテール
     * ランプにするにはコード側で色を掛けるしかない。一方、Blender で展開してテクスチャに
     * 色を描いたなら、そこへさらに赤を掛けると<b>二重に掛かって暗い赤茶になる</b>。
     * 展開されているかで自動的に切り替える。</p>
     *
     * <p>どちらの場合も明暗（{@code tint} と明るさ最大）はそのまま効かせる。ここまで
     * 手放すと点いているのか消えているのか見て分からなくなる。</p>
     */
    private static void lens(ObjModel model, PoseStack pose, VertexConsumer consumer,
                             int packedLight, String name, float[] color, float power) {
        if (!model.hasGroup(name)) {
            return;
        }
        float[] hue = model.isUvMapped(name) ? TEXTURED_COLOR : color;
        // 点いている部分だけ明るさを最大にする。0xF000F0 は「空も松明も最大」
        int light = power > 0.0F ? LightTexture.FULL_BRIGHT : packedLight;
        float tint = OFF_DIM + (1.0F - OFF_DIM) * power;
        model.render(pose.last(), consumer, light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                hue[0] * tint, hue[1] * tint, hue[2] * tint, 1.0F, name);
    }

    /**
     * 光の効果（路面・光の筋・光芒）をまとめて描く。
     *
     * <p><b>車体もタイヤも描き終えた後に、姿勢を掛ける前の {@code pose} で呼ぶこと。</b>
     * どれも深度を書かないので、先に描くと後から描いた車体に上書きされ、
     * 車体の手前にあるはずの光芒が消える。</p>
     */
    public static void renderEffects(CarEntity car, CarModel carModel, float partialTick,
                                     PoseStack pose, MultiBufferSource buffer) {
        ObjModel body = ObjModel.get(carModel.bodyModel());
        boolean head = car.isHeadlightOn() && body.hasGroup(HEAD);
        boolean tail = (car.isHeadlightOn() || car.isBrakeLightOn()) && body.hasGroup(TAIL);
        boolean reverse = car.isReverseLightOn() && body.hasGroup(REVERSE);
        if (!head && !tail && !reverse) {
            return;
        }

        // 車体と同じ変換を、姿勢を掛ける前の原点から組み直す。ここで出た位置は
        // 「車の補間位置を原点にした世界の向き」になる
        PoseStack rig = new PoseStack();
        CarObjRenderer.applyRotations(car, rig, partialTick);
        CarObjRenderer.applyBodyTransform(carModel, car.getSpec(), rig);
        Matrix4f bodyMatrix = rig.last().pose();

        Vec3 origin = car.getPosition(partialTick);
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 eye = camera.getPosition().subtract(origin);
        float darkness = darkness(car);
        Matrix4f matrix = pose.last().pose();

        Vec3 forward = direction(bodyMatrix, 0.0F, 0.0F, -1.0F);
        Vec3 bodyUp = direction(bodyMatrix, 0.0F, 1.0F, 0.0F);

        Beam beam = null;
        if (head) {
            beam = Beam.of(transform(bodyMatrix, body.groupSideCenters(HEAD)), forward, bodyUp,
                    suspensionPitch(car, partialTick));
        }

        // 掛け算の層は加算の層より先に、まとめて流す。種類の違うバッファを交互に取ると、
        // 共有のバッファがそのたびに流されてしまう
        // 画素ごとに解く光（HeadlightVolume）が使えるなら、路面と光の筋はそちらに任せる。
        // こちらは光芒だけ描く。使えないとき（シェーダーパックなど）はここで形を積んで代用する
        boolean screenSpace = HeadlightVolume.isActive();
        if (beam != null && !screenSpace && !MirrorRenderer.isRendering()) {
            float strength = darkness;
            if (strength > 0.03F && eye.lengthSqr() < GROUND_MAX_DISTANCE * GROUND_MAX_DISTANCE) {
                GroundField field = FIELDS.computeIfAbsent(car, key -> new GroundField());
                if (field.tick != car.tickCount) {
                    field.rebuild(car.level(), origin, beam);
                    field.tick = car.tickCount;
                }
                Lighting lit = renderGround(car.level(), field, beam, origin, strength);
                if (lit != null) {
                    if (lit.textured.count > 0) {
                        lit.textured.emit(pose.last(), buffer.getBuffer(KurumaRenderTypes.LIT_SURFACE));
                    }
                    if (lit.glow.count > 0) {
                        lit.glow.emit(matrix, buffer.getBuffer(KurumaRenderTypes.LIGHT_ADDITIVE), GROUND_ADD);
                    }
                }
                beam.field = field;
            }
        }

        VertexConsumer additive = buffer.getBuffer(KurumaRenderTypes.LIGHT_ADDITIVE);
        if (beam != null && !screenSpace) {
            float volume = VOLUME_DAYLIGHT + (1.0F - VOLUME_DAYLIGHT) * darkness;
            for (Vec3 lamp : beam.lamps) {
                renderVolume(matrix, additive, beam, lamp, eye, origin, volume);
            }
        }

        float glow = GLOW_DAYLIGHT + (1.0F - GLOW_DAYLIGHT) * darkness;
        if (head) {
            for (Vec3 lamp : beam.lamps) {
                renderGlows(matrix, additive, camera, lamp, forward, eye, HEAD_GLOW_RADIUS,
                        BEAM_COLOR, glow, true);
            }
        }
        if (tail) {
            boolean braking = car.isBrakeLightOn();
            // ブレーキは昼間でもはっきり見えないと困る。テールだけのときは夜だけ
            float power = braking ? Math.max(0.55F, glow) : glow * 0.55F;
            for (Vec3 lamp : transform(bodyMatrix, body.groupSideCenters(TAIL))) {
                renderGlows(matrix, additive, camera, lamp, forward.scale(-1.0), eye,
                        braking ? BRAKE_GLOW_RADIUS : TAIL_GLOW_RADIUS, TAIL_COLOR, power, false);
            }
        }
        if (reverse) {
            for (Vec3 lamp : transform(bodyMatrix, body.groupSideCenters(REVERSE))) {
                renderGlows(matrix, additive, camera, lamp, forward.scale(-1.0), eye,
                        REVERSE_GLOW_RADIUS, REVERSE_COLOR, glow * 0.8F, false);
            }
        }
    }

    /**
     * {@link HeadlightVolume} へ渡すランプを {@code out[start..]} に詰める。詰めた数を返す。
     *
     * <p>1 灯を行列 1 つで渡す。列 0 = 位置（カメラ基準）と届く距離、列 1 = 光軸と強さ、
     * 列 2 = 右、列 3 = 上。</p>
     *
     * <p><b>届く距離は光軸に沿って 1 本だけ光線を飛ばして決める。</b>画素ごとの計算は
     * 深度しか知らないので、そのままでは壁の向こう側の霧まで光る（横から見ると光線が
     * 壁を突き抜けて見える）。</p>
     */
    static int collectLamps(CarEntity car, float partialTick, Vec3 camera, Matrix4f[] out, int start) {
        CarModel carModel = CarModel.get(car.getCarId());
        ObjModel body = ObjModel.get(carModel.bodyModel());
        if (!car.isHeadlightOn() || !body.hasGroup(HEAD)) {
            return 0;
        }
        PoseStack rig = new PoseStack();
        CarObjRenderer.applyRotations(car, rig, partialTick);
        CarObjRenderer.applyBodyTransform(carModel, car.getSpec(), rig);
        Matrix4f bodyMatrix = rig.last().pose();
        Beam beam = Beam.of(transform(bodyMatrix, body.groupSideCenters(HEAD)),
                direction(bodyMatrix, 0.0F, 0.0F, -1.0F), direction(bodyMatrix, 0.0F, 1.0F, 0.0F),
                suspensionPitch(car, partialTick));
        Vec3 origin = car.getPosition(partialTick);
        float strength = darkness(car);
        int count = 0;
        for (Vec3 lamp : beam.lamps) {
            if (start + count >= out.length) {
                break;
            }
            Vec3 from = origin.add(lamp);
            Vec3 to = from.add(beam.axis.scale(REACH_PROBE));
            BlockHitResult hit = car.level().clip(new ClipContext(from, to,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, car));
            double reach = hit.getType() == HitResult.Type.MISS ? 1000.0 : hit.getLocation().distanceTo(from);
            Vec3 at = from.subtract(camera);
            Matrix4f m = new Matrix4f();
            m.setColumn(0, new Vector4f((float) at.x, (float) at.y, (float) at.z, (float) reach));
            m.setColumn(1, new Vector4f((float) beam.axis.x, (float) beam.axis.y, (float) beam.axis.z, strength));
            m.setColumn(2, new Vector4f((float) beam.right.x, (float) beam.right.y, (float) beam.right.z, 0.0F));
            m.setColumn(3, new Vector4f((float) beam.up.x, (float) beam.up.y, (float) beam.up.z, 0.0F));
            out[start + count] = m;
            count++;
        }
        return count;
    }

    /** 届く距離を調べる光線の長さ [m]。光線の描く長さ（シェーダーの VOLUME_LENGTH）より少し長く。 */
    private static final double REACH_PROBE = 48.0;

    /**
     * サスの沈み込みで生じた前後の傾き [rad]。正で鼻上げ（前のサスが長い）。
     *
     * <p>車体の姿勢（ピッチ）をそのまま使ってはいけない。あちらは坂の傾きも含むので、
     * 打ち消すと坂道で光が路面に沿わなくなる。前後のサスの長さの差なら、路面に対する
     * 車体の傾きだけが取れる——実車のオートレベライザーが車高センサーで測っているのと同じ量。</p>
     */
    private static double suspensionPitch(CarEntity car, float partialTick) {
        double front = 0.0;
        double rear = 0.0;
        for (com.jdmmc.kurumamod.physics.Wheel wheel : com.jdmmc.kurumamod.physics.Wheel.VALUES) {
            double length = car.getRenderSuspensionLength(wheel, partialTick);
            if (wheel.isFront()) {
                front += length * 0.5;
            } else {
                rear += length * 0.5;
            }
        }
        return Math.atan2(front - rear, car.getSpec().wheelBase());
    }

    /** 周りの暗さ 0..1。松明の下や昼間は 0 に近づく。 */
    private static float darkness(CarEntity car) {
        BlockPos pos = car.blockPosition();
        int block = car.level().getBrightness(LightLayer.BLOCK, pos);
        // 空の明るさは時刻で減る。
        //
        // <b>int の getSkyDarken() を使ってはいけない。クライアントでは更新されず、夜でも 0 のまま</b>
        // （サーバーの tick でしか計算されない）。そのせいで夜なのに「真昼」と判定し、光の強さが
        // ずっと 0 だった——路面が照らされない、光線がうっすらとしか出ない、の原因はすべてこれ。
        // クライアントの getSkyDarken(partialTick) は時刻と雨から毎回求めた空の明るさで、
        // 夜 0.2・真昼 1.0 を返す
        float daylight = car.level() instanceof net.minecraft.client.multiplayer.ClientLevel client
                ? Mth.clamp((client.getSkyDarken(1.0F) - 0.2F) / 0.8F, 0.0F, 1.0F)
                : 1.0F;
        float sky = car.level().getBrightness(LightLayer.SKY, pos) * daylight;
        float ambient = Math.max(block, sky);
        return Mth.clamp(1.0F - ambient / 12.0F, 0.0F, 1.0F);
    }

    // ---- 配光 ----------------------------------------------------------------------

    /**
     * ヘッドライト 2 灯ぶんの位置と光軸。
     *
     * <p>光軸は<b>車体の前方を {@link #AIM_DOWN} だけ下げたもの</b>。レンズの平均法線から
     * 取ろうとしたことがあるが、箱で作ると法線が打ち消し合って向きが定まらない。</p>
     */
    private static final class Beam {
        final List<Vec3> lamps;
        final Vec3 mid;
        final Vec3 axis;
        final Vec3 right;
        final Vec3 up;
        /** 光の筋の裾を路面で消すのに使う。路面を調べていないときは null。 */
        GroundField field;

        private Beam(List<Vec3> lamps, Vec3 axis, Vec3 right, Vec3 up) {
            this.lamps = lamps;
            Vec3 sum = Vec3.ZERO;
            for (Vec3 lamp : lamps) {
                sum = sum.add(lamp);
            }
            this.mid = lamps.isEmpty() ? Vec3.ZERO : sum.scale(1.0 / lamps.size());
            this.axis = axis;
            this.right = right;
            this.up = up;
        }

        /**
         * @param suspensionPitch サスの沈み込みで生じた前後の傾き [rad]。正で鼻上げ。
         *                        {@link #LEVELING} の割合だけ打ち消す
         */
        static Beam of(List<Vec3> lamps, Vec3 forward, Vec3 bodyUp, double suspensionPitch) {
            Vec3 right = forward.cross(bodyUp).normalize();
            Vec3 up = right.cross(forward).normalize();
            // 下げる角度 = 取り付けの下向き + 沈み込みの戻し（鼻下げなら上へ戻す）
            // 調整画面（Ctrl+D、デバッグ用）で動かせる。既定は AIM_DOWN と LEVELING と同じ値
            double aim = Math.toRadians(LightDebugScreen.Values.aimDown)
                    + suspensionPitch * LightDebugScreen.Values.leveling;
            double cos = Math.cos(aim);
            double sin = Math.sin(aim);
            Vec3 axis = forward.scale(cos).subtract(up.scale(sin)).normalize();
            Vec3 beamUp = up.scale(cos).add(forward.scale(sin)).normalize();
            return new Beam(lamps, axis, right, beamUp);
        }

        /**
         * 点 (x, y, z) の面（法線 n）が受ける照度。2 灯の合計。
         *
         * <p>配光 × 入射角の余弦 ÷ 距離²。<b>カットオフより上は照らさない</b>ので、
         * 壁には光の上端の線が出て、遠くの路面は光軸が当たる手前で自然に途切れる。</p>
         */
        double illuminance(double x, double y, double z, double nx, double ny, double nz) {
            double total = 0.0;
            for (Vec3 lamp : lamps) {
                double vx = x - lamp.x;
                double vy = y - lamp.y;
                double vz = z - lamp.z;
                double dist2 = vx * vx + vy * vy + vz * vz;
                if (dist2 < 0.01) {
                    continue;
                }
                double along = vx * axis.x + vy * axis.y + vz * axis.z;
                if (along <= 0.05) {
                    continue;
                }
                double cosIncidence = -(vx * nx + vy * ny + vz * nz) / Math.sqrt(dist2);
                if (cosIncidence <= 0.0) {
                    continue;
                }
                double tx = (vx * right.x + vy * right.y + vz * right.z) / along;
                double ty = (vx * up.x + vy * up.y + vz * up.z) / along;
                total += pattern(tx, ty) * cosIncidence / dist2;
            }
            return total;
        }
    }

    /** 光軸からの正接 (tx, ty) に出る光の強さ。光軸上でおよそ 1。 */
    private static double pattern(double tx, double ty) {
        // 歩道側（左 = tx が負）はカットオフが持ち上がる
        double cut = CUTOFF + Math.min(KICK_MAX, Math.max(0.0, -tx) * KICK_SLOPE);
        double gate = 1.0 - smoothstep(-CUTOFF_SOFT, CUTOFF_SOFT, ty - cut);
        if (gate <= 0.0) {
            return 0.0;
        }
        double wx = tx / WIDE_X;
        double wy = ty / WIDE_Y;
        double sx = tx / SPOT_X;
        double sy = (ty - SPOT_CENTER) / SPOT_Y;
        return gate * (WIDE_WEIGHT * Math.exp(-(wx * wx + wy * wy))
                + SPOT_WEIGHT * Math.exp(-(sx * sx + sy * sy)));
    }

    /** 照度を画面の明るさ 0..1 へ。 */
    private static float exposure(double illuminance) {
        if (illuminance <= 0.0) {
            return 0.0F;
        }
        return (float) (1.0 - Math.exp(-Math.pow(illuminance * EXPOSURE_GAIN, EXPOSURE_GAMMA)));
    }

    // ---- 路面 ----------------------------------------------------------------------

    /**
     * 車の前に広がる扇形の範囲で、ブロックごとの路面の高さを調べたもの。
     *
     * <p><b>1 ティックに 1 度だけ作り直す。</b>世界の形はそうそう変わらないのに対し、
     * 明るさは毎フレーム計算し直す（ライトの位置は補間されて動くので、20Hz で計算すると
     * 照らされた範囲が車からカクカク遅れる）。</p>
     */
    private static final class GroundField {
        static final byte NONE = 0;
        static final byte FLOOR = 1;
        /** ライトの高さにブロックがある。天面は照らさず、手前の面だけが照らされる。 */
        static final byte WALL = 2;

        int tick = Integer.MIN_VALUE;
        int minX;
        int minZ;
        int sizeX;
        int sizeZ;
        double lampY;
        float[] top = new float[0];
        byte[] kind = new byte[0];
        /** 壁の当たり判定の水平の範囲（フェンスや塀は 1 ブロックいっぱいではない）。 */
        float[] wall = new float[0];
        /** 床のブロックの高さ（Y 座標）。照らした面をそのブロックのテクスチャで描くのに使う。 */
        int[] floorY = new int[0];
        /** 光源に近い順に並べたセルの番号。遮蔽を手前から積むのに使う。 */
        int[] order = new int[0];
        int orderCount;

        int index(int bx, int bz) {
            int ix = bx - minX;
            int iz = bz - minZ;
            if (ix < 0 || iz < 0 || ix >= sizeX || iz >= sizeZ) {
                return -1;
            }
            return iz * sizeX + ix;
        }

        /** その位置の床の高さ。調べていないか床でなければ NaN。 */
        double floorAt(double x, double z) {
            int i = index(Mth.floor(x), Mth.floor(z));
            return i >= 0 && kind[i] == FLOOR ? top[i] : Double.NaN;
        }

        void rebuild(Level level, Vec3 origin, Beam beam) {
            // 範囲は光軸の水平の向きで決める。調べるのはブロックの座標なので世界の向き
            double fx = beam.axis.x;
            double fz = beam.axis.z;
            double length = Math.hypot(fx, fz);
            if (length < 1.0E-4) {
                sizeX = sizeZ = 0;
                orderCount = 0;
                return;
            }
            fx /= length;
            fz /= length;
            double rx = -fz;
            double rz = fx;
            double ox = origin.x + beam.mid.x;
            double oz = origin.z + beam.mid.z;
            lampY = origin.y + beam.mid.y;

            // 扇形を囲む箱
            double far = GROUND_RANGE + 2.0;
            double farHalf = halfWidth(GROUND_RANGE) + 2.0;
            double[][] corners = {
                    {ox - fx * 2.0 + rx * 3.0, oz - fz * 2.0 + rz * 3.0},
                    {ox - fx * 2.0 - rx * 3.0, oz - fz * 2.0 - rz * 3.0},
                    {ox + fx * far + rx * farHalf, oz + fz * far + rz * farHalf},
                    {ox + fx * far - rx * farHalf, oz + fz * far - rz * farHalf},
            };
            double lowX = Double.MAX_VALUE;
            double lowZ = Double.MAX_VALUE;
            double highX = -Double.MAX_VALUE;
            double highZ = -Double.MAX_VALUE;
            for (double[] c : corners) {
                lowX = Math.min(lowX, c[0]);
                highX = Math.max(highX, c[0]);
                lowZ = Math.min(lowZ, c[1]);
                highZ = Math.max(highZ, c[1]);
            }
            minX = Mth.floor(lowX);
            minZ = Mth.floor(lowZ);
            sizeX = Mth.floor(highX) - minX + 1;
            sizeZ = Mth.floor(highZ) - minZ + 1;
            int cells = sizeX * sizeZ;
            if (top.length < cells) {
                top = new float[cells];
                kind = new byte[cells];
                wall = new float[cells * 4];
                floorY = new int[cells];
                order = new int[cells];
            }
            Arrays.fill(kind, 0, cells, NONE);

            long[] sortable = new long[cells];
            int count = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int lampBlock = Mth.floor(lampY);
            for (int iz = 0; iz < sizeZ; iz++) {
                for (int ix = 0; ix < sizeX; ix++) {
                    int bx = minX + ix;
                    int bz = minZ + iz;
                    double dx = bx + 0.5 - ox;
                    double dz = bz + 0.5 - oz;
                    double f = dx * fx + dz * fz;
                    double l = dx * rx + dz * rz;
                    if (f < -1.5 || f > GROUND_RANGE + 1.0 || Math.abs(l) > halfWidth(Math.max(0.0, f)) + 1.5) {
                        continue;
                    }
                    int i = iz * sizeX + ix;
                    if (!scan(level, pos, bx, bz, lampBlock, i)) {
                        continue;
                    }
                    // 距離を上位、番号を下位に詰めて並べ替える（1mm 単位）
                    sortable[count++] = ((long) (Math.sqrt(dx * dx + dz * dz) * 1000.0) << 24) | i;
                }
            }
            Arrays.sort(sortable, 0, count);
            for (int k = 0; k < count; k++) {
                order[k] = (int) (sortable[k] & 0xFFFFFF);
            }
            orderCount = count;
        }

        /**
         * 1 列ぶんの路面を調べる。
         *
         * <p><b>ライトの高さから始める。</b>上から下へ舐めると、トンネルの天井や
         * 高架を「路面」と読んで、その下の道路が照らされなくなる。ライトの高さに
         * ブロックがあれば壁、無ければそこから下へ床を探す。</p>
         */
        private boolean scan(Level level, BlockPos.MutableBlockPos pos, int bx, int bz,
                             int lampBlock, int i) {
            double t = surfaceTop(level, pos.set(bx, lampBlock, bz));
            if (!Double.isNaN(t) && t >= lampY - 0.05) {
                // 壁。水平の範囲は当たり判定の箱から取る
                BlockState state = level.getBlockState(pos);
                VoxelShape shape = state.getCollisionShape(level, pos);
                AABB box = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
                wall[i * 4] = (float) (bx + box.minX);
                wall[i * 4 + 1] = (float) (bx + box.maxX);
                wall[i * 4 + 2] = (float) (bz + box.minZ);
                wall[i * 4 + 3] = (float) (bz + box.maxZ);
                double height = t;
                for (int y = lampBlock + 1; y <= lampBlock + WALL_SCAN_HEIGHT; y++) {
                    double above = surfaceTop(level, pos.set(bx, y, bz));
                    if (Double.isNaN(above)) {
                        break;
                    }
                    height = above;
                }
                top[i] = (float) height;
                kind[i] = WALL;
                return true;
            }
            if (!Double.isNaN(t)) {
                top[i] = (float) t;
                kind[i] = FLOOR;
                floorY[i] = lampBlock;
                return true;
            }
            for (int y = lampBlock - 1; y >= lampBlock - GROUND_SCAN_DEPTH; y--) {
                double below = surfaceTop(level, pos.set(bx, y, bz));
                if (!Double.isNaN(below)) {
                    top[i] = (float) below;
                    kind[i] = FLOOR;
                    floorY[i] = y;
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * そのブロックの上面の高さ。当たり判定も液体も無ければ NaN。
     *
     * <p>液体も面として扱う（水面が照らされ、その下の水底は照らされない）。
     * 草や花は当たり判定が無いので素通りして、下の地面が照らされる。</p>
     *
     * <p><b>当たり判定だけで見てはいけない。</b>雪の層 1 枚や、道路 MOD のアスファルトの薄い層は
     * 当たり判定が空なので素通りしてしまい、<b>その下のブロックの上面</b>を照らすことになる。
     * そこは層に覆われて見えないので、路面がまったく明るくならなかった。見た目の形が
     * <b>1 ブロックの床いっぱいに広がっているもの</b>は面として数える（草花は広がっていないので素通り）。</p>
     */
    private static double surfaceTop(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return Double.NaN;
        }
        double top = Double.NaN;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        VoxelShape outline = state.getShape(level, pos);
        if (!outline.isEmpty()) {
            AABB box = outline.bounds();
            if (box.minX < 0.01 && box.maxX > 0.99 && box.minZ < 0.01 && box.maxZ > 0.99) {
                double visible = pos.getY() + box.maxY;
                top = Double.isNaN(top) ? visible : Math.max(top, visible);
            }
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            double surface = pos.getY() + fluid.getHeight(level, pos);
            top = Double.isNaN(top) ? surface : Math.max(top, surface);
        }
        return top;
    }

    /** 光軸から横へどこまで調べるか [m]。手前は広く、奥は配光の芯のぶんだけ。 */
    private static double halfWidth(double forward) {
        return Math.min(forward * Math.tan(Math.toRadians(60.0)) + 1.5,
                3.5 + forward * Math.tan(Math.toRadians(22.0)));
    }

    /**
     * 路面の照り返しを組み立てる。
     *
     * <p>ブロックごとに<b>上面と、光源を向いている側面</b>を、<b>そのブロックのモデルの面</b>として
     * 明るさ最大で描き直し、頂点ごとに配光から求めた濃さで重ねる（{@link KurumaRenderTypes#LIT_SURFACE}）。
     * 照らされた路面は模様も色もそのまま明るくなる。頂点はブロックの角にあるので、
     * 隣どうしで明るさが途切れない。</p>
     *
     * <h3>遮蔽</h3>
     *
     * <p>光源に近いセルから順に、光軸の左右の角度ごとに「これまでに見えた最も高い仰角」を
     * 積んでいく（地平線）。自分の仰角がそれより低ければ、手前の何かの陰にいる。
     * 平らな地面は奥ほど仰角が上がるので自分の陰にはならず、段差や塀の向こうだけが暗くなる。
     * 光線を 1 本ずつ飛ばすより桁違いに安い。</p>
     */
    private static Lighting renderGround(Level level, GroundField field, Beam beam, Vec3 origin,
                                         float strength) {
        if (field.orderCount == 0) {
            return null;
        }
        double fx = beam.axis.x;
        double fz = beam.axis.z;
        double length = Math.hypot(fx, fz);
        if (length < 1.0E-4) {
            return null;
        }
        fx /= length;
        fz /= length;
        double rx = -fz;
        double rz = fx;
        double mx = origin.x + beam.mid.x;
        double mz = origin.z + beam.mid.z;
        double lampY = origin.y + beam.mid.y;

        float[] horizon = new float[HORIZON_BINS];
        Arrays.fill(horizon, Float.NEGATIVE_INFINITY);
        double binScale = HORIZON_BINS / Math.PI;
        Lighting lighting = new Lighting(level, beam, origin, strength, mx, mz, fx, fz, rx, rz);
        Vertices out = lighting.glow;

        for (int k = 0; k < field.orderCount; k++) {
            int i = field.order[k];
            int bx = field.minX + i % field.sizeX;
            int bz = field.minZ + i / field.sizeX;
            double dx = bx + 0.5 - mx;
            double dz = bz + 0.5 - mz;
            double f = dx * fx + dz * fz;
            double l = dx * rx + dz * rz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.4 || f <= 0.0) {
                continue;
            }
            double angle = Math.atan2(l, f);
            int bin = Mth.clamp((int) ((angle + Math.PI / 2.0) * binScale), 0, HORIZON_BINS - 1);
            int spread = Math.max(0, (int) Math.ceil(Math.atan(0.75 / dist) * binScale));
            float seen = horizon[bin];
            double top = field.top[i];

            if (field.kind[i] == GroundField.FLOOR) {
                if ((top - lampY) / dist >= seen - 1.0E-4
                        && !lighting.surface(bx, field.floorY[i], bz, Direction.UP)) {
                    // モデルの面が無い（水面など）。テクスチャ無しの光だけ置く
                    float y = (float) (top + SURFACE_LIFT);
                    lighting.quad(out, bx, y, bz, bx + 1, y, bz, bx + 1, y, bz + 1, bx, y, bz + 1,
                            0.0, 1.0, 0.0);
                }
            }

            // 光源を向いている側面。手前（光源側）の隣が低ければ、そのぶんだけ面が見えている
            double faceTop = Math.min(top, lampY + WALL_SCAN_HEIGHT);
            wallFace(field, lighting, out, i, bx, bz, -1, 0, mx, mz, lampY, dist, seen, faceTop);
            wallFace(field, lighting, out, i, bx, bz, 1, 0, mx, mz, lampY, dist, seen, faceTop);
            wallFace(field, lighting, out, i, bx, bz, 0, -1, mx, mz, lampY, dist, seen, faceTop);
            wallFace(field, lighting, out, i, bx, bz, 0, 1, mx, mz, lampY, dist, seen, faceTop);

            float elevation = (float) ((top - lampY) / dist);
            for (int b = Math.max(0, bin - spread); b <= Math.min(HORIZON_BINS - 1, bin + spread); b++) {
                if (elevation > horizon[b]) {
                    horizon[b] = elevation;
                }
            }
        }
        return lighting;
    }

    /** セル i の、(sx, sz) 側の側面。見えていて照らされていれば縦に刻んで出す。 */
    private static void wallFace(GroundField field, Lighting lighting, Vertices out, int i,
                                 int bx, int bz, int sx, int sz, double mx, double mz, double lampY,
                                 double dist, float seen, double faceTop) {
        // 光源を向いていない面は照らされない
        double toLampX = mx - (bx + 0.5);
        double toLampZ = mz - (bz + 0.5);
        if (sx * toLampX + sz * toLampZ <= 0.0) {
            return;
        }
        int neighbour = field.index(bx + sx, bz + sz);
        if (neighbour < 0 || field.kind[neighbour] == GroundField.NONE
                || field.kind[neighbour] == GroundField.WALL) {
            return;
        }
        double bottom = field.top[neighbour];
        if (faceTop - bottom < 0.1) {
            return;
        }
        // 面の下端が手前の何かに隠れていれば、面全体を隠れたものとする
        if ((bottom - lampY) / dist < seen - 1.0E-4) {
            return;
        }
        double x0;
        double x1;
        double z0;
        double z1;
        if (field.kind[i] == GroundField.WALL) {
            x0 = field.wall[i * 4];
            x1 = field.wall[i * 4 + 1];
            z0 = field.wall[i * 4 + 2];
            z1 = field.wall[i * 4 + 3];
        } else {
            x0 = bx;
            x1 = bx + 1;
            z0 = bz;
            z1 = bz + 1;
        }
        // まずはブロックのモデルの面として描く。1 ブロックずつ積む
        Direction side = Direction.fromDelta(sx, 0, sz);
        boolean textured = false;
        for (int y = Mth.floor(bottom + 1.0E-3); y <= Mth.floor(faceTop - 1.0E-3); y++) {
            textured |= lighting.surface(bx, y, bz, side);
        }
        if (textured) {
            return;
        }
        float lift = SURFACE_LIFT;
        int slices = Mth.clamp((int) Math.ceil((faceTop - bottom) / WALL_SLICE), 1, 16);
        for (int s = 0; s < slices; s++) {
            float ya = (float) (bottom + (faceTop - bottom) * s / slices);
            float yb = (float) (bottom + (faceTop - bottom) * (s + 1) / slices);
            if (sx != 0) {
                float x = (float) (sx < 0 ? x0 - lift : x1 + lift);
                lighting.quad(out, x, ya, (float) z0, x, ya, (float) z1, x, yb, (float) z1, x, yb, (float) z0,
                        sx, 0.0, 0.0);
            } else {
                float z = (float) (sz < 0 ? z0 - lift : z1 + lift);
                lighting.quad(out, (float) x0, ya, z, (float) x1, ya, z, (float) x1, yb, z, (float) x0, yb, z,
                        0.0, 0.0, sz);
            }
        }
    }

    /** 路面の頂点の明るさを求めて積む係。範囲の端で消していくのもここ。 */
    private static final class Lighting {
        /** 照らされた面を、そのブロックのテクスチャで描き直したもの。 */
        final Textured textured = new Textured();
        /** 光の色味として足すもの。 */
        final Vertices glow = new Vertices();
        final Level level;
        final BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        final BlockColors colors = Minecraft.getInstance().getBlockColors();
        final RandomSource random = RandomSource.create();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final Beam beam;
        final Vec3 origin;
        final float strength;
        final double mx;
        final double mz;
        final double fx;
        final double fz;
        final double rx;
        final double rz;

        Lighting(Level level, Beam beam, Vec3 origin, float strength, double mx, double mz,
                 double fx, double fz, double rx, double rz) {
            this.level = level;
            this.beam = beam;
            this.origin = origin;
            this.strength = strength;
            this.mx = mx;
            this.mz = mz;
            this.fx = fx;
            this.fz = fz;
            this.rx = rx;
            this.rz = rz;
        }

        void quad(Vertices out, float x0, float y0, float z0, float x1, float y1, float z1,
                  float x2, float y2, float z2, float x3, float y3, float z3,
                  double nx, double ny, double nz) {
            float b0 = brightness(x0, y0, z0, nx, ny, nz);
            float b1 = brightness(x1, y1, z1, nx, ny, nz);
            float b2 = brightness(x2, y2, z2, nx, ny, nz);
            float b3 = brightness(x3, y3, z3, nx, ny, nz);
            if (b0 + b1 + b2 + b3 < 0.01F) {
                return;
            }
            float k = UNTEXTURED_BOOST;
            out.add(x0 - origin.x, y0 - origin.y, z0 - origin.z, b0 * k);
            out.add(x1 - origin.x, y1 - origin.y, z1 - origin.z, b1 * k);
            out.add(x2 - origin.x, y2 - origin.y, z2 - origin.z, b2 * k);
            out.add(x3 - origin.x, y3 - origin.y, z3 - origin.z, b3 * k);
        }

        /**
         * ブロック (x, y, z) の、{@code side} を向いたモデルの面を照らして積む。
         *
         * <p>面の一覧は描画と同じ乱数の種で引く（草のように向きがばらつくテクスチャで、
         * 照らした面だけ模様がずれないように）。色の付く面（草の上面・葉）は
         * {@link BlockColors} で染める。付けないと草が灰色に光る。</p>
         *
         * @return モデルにその向きの面があったか（照らされたかどうかではない）
         */
        boolean surface(int x, int y, int z, Direction side) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.getRenderShape() != RenderShape.MODEL) {
                return false;
            }
            BakedModel model = blocks.getBlockModel(state);
            // Forge のモデルデータを通す。通さないと、接続して形が変わるブロック（他 MOD の道路など）で
            // 面が取れないことがある
            ModelData data = level.getModelDataManager().getAt(pos);
            data = model.getModelData(level, pos, state, data == null ? ModelData.EMPTY : data);
            long seed = state.getSeed(pos);
            boolean found = false;
            random.setSeed(seed);
            for (RenderType layer : model.getRenderTypes(state, random, data)) {
                random.setSeed(seed);
                for (BakedQuad quad : model.getQuads(state, side, random, data, layer)) {
                    found |= emitQuad(state, quad, x, y, z);
                }
                random.setSeed(seed);
                for (BakedQuad quad : model.getQuads(state, null, random, data, layer)) {
                    if (quad.getDirection() == side) {
                        found |= emitQuad(state, quad, x, y, z);
                    }
                }
            }
            return found;
        }

        private boolean emitQuad(BlockState state, BakedQuad quad, int x, int y, int z) {
            Direction face = quad.getDirection();
            double nx = face.getStepX();
            double ny = face.getStepY();
            double nz = face.getStepZ();
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            float[] px = new float[4];
            float[] py = new float[4];
            float[] pz = new float[4];
            float[] b = new float[4];
            float sum = 0.0F;
            for (int v = 0; v < 4; v++) {
                int o = v * stride;
                px[v] = (float) (x + Float.intBitsToFloat(data[o]) + nx * SURFACE_LIFT);
                py[v] = (float) (y + Float.intBitsToFloat(data[o + 1]) + ny * SURFACE_LIFT);
                pz[v] = (float) (z + Float.intBitsToFloat(data[o + 2]) + nz * SURFACE_LIFT);
                b[v] = brightness(px[v], py[v], pz[v], nx, ny, nz);
                sum += b[v];
            }
            if (sum < 0.01F) {
                return true;
            }
            float r = 1.0F;
            float g = 1.0F;
            float bl = 1.0F;
            if (quad.isTinted()) {
                int color = colors.getColor(state, level, pos, quad.getTintIndex());
                r = (color >> 16 & 0xFF) / 255.0F;
                g = (color >> 8 & 0xFF) / 255.0F;
                bl = (color & 0xFF) / 255.0F;
            }
            // 光の色も掛ける。昼の白い光で描くと、照らされたところだけ真昼に見える
            r *= BEAM_COLOR[0];
            g *= BEAM_COLOR[1];
            bl *= BEAM_COLOR[2];
            for (int v = 0; v < 4; v++) {
                int o = v * stride;
                float rx = px[v] - (float) origin.x;
                float ry = py[v] - (float) origin.y;
                float rz = pz[v] - (float) origin.z;
                textured.add(rx, ry, rz, r, g, bl, b[v] * LIT_MAX,
                        Float.intBitsToFloat(data[o + 4]), Float.intBitsToFloat(data[o + 5]),
                        (float) nx, (float) ny, (float) nz);
                glow.add(rx, ry, rz, b[v]);
            }
            return true;
        }

        private float brightness(double x, double y, double z, double nx, double ny, double nz) {
            double dx = x - mx;
            double dz = z - mz;
            double f = dx * fx + dz * fz;
            double l = Math.abs(dx * rx + dz * rz);
            // 調べた範囲の奥と横の端でなめらかに消す。切れ目がそのまま見えないように
            double fade = (1.0 - smoothstep(GROUND_FADE_START, GROUND_RANGE, f))
                    * (1.0 - smoothstep(halfWidth(Math.max(0.0, f)) - 2.5, halfWidth(Math.max(0.0, f)), l));
            if (fade <= 0.0) {
                return 0.0F;
            }
            double e = beam.illuminance(x - origin.x, y - origin.y, z - origin.z, nx, ny, nz);
            return (float) (strength * fade) * exposure(e);
        }
    }

    // ---- 光の筋 --------------------------------------------------------------------

    /**
     * 光の筋。<b>空気中の塵が光って見える</b>層を、光軸を包む殻として描く。
     *
     * <ul>
     *   <li><b>上の縁はカットオフ</b>で水平に近く、下の縁は路面へ向かって落ちる。
     *       ロービームの光は円錐ではなく、路面へ向かう薄い板に近い</li>
     *   <li><b>殻を斜めから見るほど薄くする。</b>光る空気の層は、厚みを真横から透かしたときに
     *       いちばん濃く、縁（接線方向から見る）では 0 になる。一様な濃さの殻は
     *       プラスチックの円錐に見える</li>
     *   <li><b>路面の近くで消す。</b>地面に潜った部分は深度テストで切られるので、
     *       そのままでは地面との交わりが硬い線になる</li>
     * </ul>
     */
    private static void renderVolume(Matrix4f matrix, VertexConsumer consumer, Beam beam, Vec3 lamp,
                                     Vec3 eye, Vec3 origin, float strength) {
        double centerY = (CUTOFF + VOLUME_BOTTOM) / 2.0;
        double halfY = (CUTOFF - VOLUME_BOTTOM) / 2.0;
        double[] ringZ = new double[VOLUME_RINGS];
        double[] ringGround = new double[VOLUME_RINGS];
        for (int r = 0; r < VOLUME_RINGS; r++) {
            // 根元を細かく刻む（形の変化が大きいのは手前）
            double z = VOLUME_LENGTH * Math.pow(r / (double) (VOLUME_RINGS - 1), 1.6) + 0.05;
            ringZ[r] = z;
            Vec3 center = lamp.add(beam.axis.scale(z));
            double ground = beam.field != null
                    ? beam.field.floorAt(origin.x + center.x, origin.z + center.z) - origin.y
                    : Double.NaN;
            ringGround[r] = ground;
        }

        float[][] ring0 = null;
        for (int r = 0; r < VOLUME_RINGS; r++) {
            float[][] ring = new float[VOLUME_SEGMENTS + 1][];
            double z = ringZ[r];
            double t = z / VOLUME_LENGTH;
            // 根元から立ち上がり、奥へ行くほど薄れる
            double along = Math.min(1.0, z / 0.8) * (1.0 - t) * (1.0 - t);
            double semiX = VOLUME_START_RADIUS + z * VOLUME_SPREAD_X;
            double semiY = VOLUME_START_RADIUS * 0.55 + z * halfY;
            for (int s = 0; s <= VOLUME_SEGMENTS; s++) {
                double phi = 2.0 * Math.PI * s / VOLUME_SEGMENTS;
                double cos = Math.cos(phi);
                double sin = Math.sin(phi);
                double lx = semiX * cos;
                double ly = z * centerY + semiY * sin;
                Vec3 pos = lamp.add(beam.axis.scale(z)).add(beam.right.scale(lx)).add(beam.up.scale(ly));
                // 楕円の法線
                Vec3 normal = beam.right.scale(cos / semiX).add(beam.up.scale(sin / semiY)).normalize();
                Vec3 view = eye.subtract(pos).normalize();
                double facing = Math.abs(normal.dot(view));
                double ground = ringGround[r];
                double groundFade = Double.isNaN(ground) ? 1.0 : smoothstep(0.0, 0.45, pos.y - ground);
                float alpha = (float) (VOLUME_ALPHA * strength * along * Math.pow(facing, 1.5) * groundFade);
                ring[s] = new float[]{(float) pos.x, (float) pos.y, (float) pos.z, alpha};
            }
            if (ring0 != null) {
                for (int s = 0; s < VOLUME_SEGMENTS; s++) {
                    colored(matrix, consumer, ring0[s], BEAM_COLOR);
                    colored(matrix, consumer, ring0[s + 1], BEAM_COLOR);
                    colored(matrix, consumer, ring[s + 1], BEAM_COLOR);
                    colored(matrix, consumer, ring[s], BEAM_COLOR);
                }
            }
            ring0 = ring;
        }
    }

    private static void colored(Matrix4f matrix, VertexConsumer consumer, float[] v, float[] color) {
        consumer.vertex(matrix, v[0], v[1], v[2]).color(color[0], color[1], color[2], v[3]).endVertex();
    }

    // ---- 光芒 ----------------------------------------------------------------------

    /**
     * レンズの前に浮かぶ光の滲み。
     *
     * <p>カメラの方を向いた円盤で、<b>ランプの正面から見たときだけ</b>強く出る
     * （横や後ろから見たヘッドライトは眩しくない）。深度テストはするので、壁や他の車の
     * 陰に入れば隠れる。レンズに埋もれないよう、カメラ側へ少し浮かせる。</p>
     */
    private static void renderGlows(Matrix4f matrix, VertexConsumer consumer, Camera camera, Vec3 lamp,
                                    Vec3 facing, Vec3 eye, double radius, float[] color, float strength,
                                    boolean streak) {
        Vec3 toEye = eye.subtract(lamp);
        double distance = toEye.length();
        if (distance < 0.5) {
            return;
        }
        toEye = toEye.scale(1.0 / distance);
        double visibility = smoothstep(-0.05, 0.55, facing.dot(toEye));
        float alpha = (float) (strength * visibility);
        if (alpha < 0.005F) {
            return;
        }
        Vector3f left = camera.getLeftVector();
        Vector3f upVector = camera.getUpVector();
        Vec3 right = new Vec3(-left.x(), -left.y(), -left.z());
        Vec3 up = new Vec3(upVector.x(), upVector.y(), upVector.z());
        Vec3 center = lamp.add(toEye.scale(Math.min(0.35, distance * 0.5)));

        for (int r = 0; r < GLOW_RING_RADIUS.length - 1; r++) {
            double r0 = GLOW_RING_RADIUS[r] * radius;
            double r1 = GLOW_RING_RADIUS[r + 1] * radius;
            float a0 = GLOW_RING_ALPHA[r] * alpha;
            float a1 = GLOW_RING_ALPHA[r + 1] * alpha;
            for (int s = 0; s < GLOW_SEGMENTS; s++) {
                double p0 = 2.0 * Math.PI * s / GLOW_SEGMENTS;
                double p1 = 2.0 * Math.PI * (s + 1) / GLOW_SEGMENTS;
                glowVertex(matrix, consumer, center, right, up, Math.cos(p0) * r0, Math.sin(p0) * r0, color, a0);
                glowVertex(matrix, consumer, center, right, up, Math.cos(p1) * r0, Math.sin(p1) * r0, color, a0);
                glowVertex(matrix, consumer, center, right, up, Math.cos(p1) * r1, Math.sin(p1) * r1, color, a1);
                glowVertex(matrix, consumer, center, right, up, Math.cos(p0) * r1, Math.sin(p0) * r1, color, a1);
            }
        }

        if (streak) {
            // 横に流れる細い光。夜に正面から見たときにだけ効く
            float a = alpha * 0.35F;
            for (int side = -1; side <= 1; side += 2) {
                double end = side * STREAK_LENGTH * radius / HEAD_GLOW_RADIUS;
                for (int half = -1; half <= 1; half += 2) {
                    double edge = half * STREAK_HEIGHT;
                    glowVertex(matrix, consumer, center, right, up, 0.0, 0.0, color, a);
                    glowVertex(matrix, consumer, center, right, up, end, 0.0, color, 0.0F);
                    glowVertex(matrix, consumer, center, right, up, end, edge, color, 0.0F);
                    glowVertex(matrix, consumer, center, right, up, 0.0, edge, color, 0.0F);
                }
            }
        }
    }

    private static void glowVertex(Matrix4f matrix, VertexConsumer consumer, Vec3 center, Vec3 right, Vec3 up,
                                   double x, double y, float[] color, float alpha) {
        consumer.vertex(matrix,
                        (float) (center.x + right.x * x + up.x * y),
                        (float) (center.y + right.y * x + up.y * y),
                        (float) (center.z + right.z * x + up.z * y))
                .color(color[0], color[1], color[2], alpha)
                .endVertex();
    }

    // ---- 小道具 --------------------------------------------------------------------

    /** 路面の頂点（位置と明るさ）。掛け算と加算の 2 層へ同じものを流すので、いったん溜める。 */
    private static final class Vertices {
        float[] data = new float[4096];
        int count;

        void add(double x, double y, double z, float brightness) {
            if ((count + 1) * 4 > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            int o = count * 4;
            data[o] = (float) x;
            data[o + 1] = (float) y;
            data[o + 2] = (float) z;
            data[o + 3] = brightness;
            count++;
        }

        /** 加算の層へ流す。明るさを α に入れる（加算は色 × α を足す）。 */
        void emit(Matrix4f matrix, VertexConsumer consumer, float gain) {
            for (int v = 0; v < count; v++) {
                int o = v * 4;
                consumer.vertex(matrix, data[o], data[o + 1], data[o + 2])
                        .color(BEAM_COLOR[0], BEAM_COLOR[1], BEAM_COLOR[2], Math.min(1.0F, data[o + 3] * gain))
                        .endVertex();
            }
        }
    }

    /** 照らされた面の頂点（位置・色・濃さ・UV・法線）。 */
    private static final class Textured {
        private static final int STRIDE = 12;
        float[] data = new float[STRIDE * 1024];
        int count;

        void add(float x, float y, float z, float r, float g, float b, float a,
                 float u, float v, float nx, float ny, float nz) {
            if ((count + 1) * STRIDE > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            int o = count * STRIDE;
            data[o] = x;
            data[o + 1] = y;
            data[o + 2] = z;
            data[o + 3] = r;
            data[o + 4] = g;
            data[o + 5] = b;
            data[o + 6] = a;
            data[o + 7] = u;
            data[o + 8] = v;
            data[o + 9] = nx;
            data[o + 10] = ny;
            data[o + 11] = nz;
            count++;
        }

        /** 明るさ最大（{@code FULL_BRIGHT}）で流す。暗さは α が受け持つ。 */
        void emit(PoseStack.Pose pose, VertexConsumer consumer) {
            Matrix4f matrix = pose.pose();
            Matrix3f normal = pose.normal();
            for (int v = 0; v < count; v++) {
                int o = v * STRIDE;
                consumer.vertex(matrix, data[o], data[o + 1], data[o + 2])
                        .color(data[o + 3], data[o + 4], data[o + 5], data[o + 6])
                        .uv(data[o + 7], data[o + 8])
                        .overlayCoords(OverlayTexture.NO_OVERLAY)
                        .uv2(LightTexture.FULL_BRIGHT)
                        .normal(normal, data[o + 9], data[o + 10], data[o + 11])
                        .endVertex();
            }
        }
    }

    private static List<Vec3> transform(Matrix4f matrix, List<Vec3> points) {
        return points.stream().map(p -> {
            Vector3f v = matrix.transformPosition(new Vector3f((float) p.x, (float) p.y, (float) p.z));
            return new Vec3(v.x(), v.y(), v.z());
        }).toList();
    }

    private static Vec3 direction(Matrix4f matrix, float x, float y, float z) {
        Vector3f v = matrix.transformDirection(new Vector3f(x, y, z)).normalize();
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
