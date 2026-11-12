package com.jdmmc.kurumamod.client.camera;

import com.jdmmc.kurumamod.config.ClientConfig;
import com.jdmmc.kurumamod.client.ui.ScreenStyle;
import com.jdmmc.kurumamod.util.Mth;

/**
 * 三人称の追跡カメラの距離・高さと、<b>車をいじる画面のあいだの構え（ショールーム）</b>。
 * カメラを実際に置くのは版の {@code CarChaseCamera}（1.20.1 はイベント、1.21.1・26.3 は {@code Camera#setup} の
 * mixin）で、ここはどこへ置くかを決める数と状態だけ。
 *
 * <p>換装・セッティング・座席の画面のあいだは、引いて、回転の中心を車のフロアまで下げ、少し下を向く。
 * 画面の下端をタイルが占めているので、車を画面の上半分へ逃がすため。ドラッグで車のまわりを回し、
 * ホイールで寄り引きできる。</p>
 */
public final class ShowroomCamera {

    /** キー 1 回で動く距離 [ブロック]。 */
    private static final double DISTANCE_STEP = 1.0;
    /** 距離の下限。0 まで詰めると車の内側に入って何も見えなくなる。 */
    private static final double MIN_DISTANCE = 1.5;
    /** 距離の上限。 */
    private static final double MAX_DISTANCE = 24.0;

    /**
     * 換装画面のあいだ、最低でもこれだけ引く。<b>全長 5m の車が枠に収まる距離。</b>
     *
     * <p>手元の設定がこれより遠ければそのまま。近寄って見たい人を引き戻さない。</p>
     */
    private static final double SHOWROOM_DISTANCE = 8.0;

    /**
     * 換装画面のあいだの<b>回転の中心</b>を、車のフロア（＝車体メッシュの原点＝接地面）から
     * どれだけ上に置くか [m]。
     *
     * <p>普段の中心は「車の位置 ＋ {@code cameraHeight}（既定 1.2m）」だが、換装画面では
     * <b>フロアのあたり</b>へ下げる。回すときに車が中心に居座るのはここが軸だからで、
     * 軸が屋根より上にあると、回すたびに車が画面の中で振り回されて見える。</p>
     *
     * <p>ぴったり接地面ではなく少し上げてあるのは、水平に構えたときにカメラが地面と
     * 同じ高さになるのを避けるため。</p>
     */
    private static final double SHOWROOM_PIVOT_LIFT = 0.30;

    /**
     * 換装画面のあいだ、これだけ余分に下を向く [度]。
     *
     * <p><b>車を上へ逃がす仕事の大半は、回転の中心を下げたことが担っている</b>
     * （{@link #SHOWROOM_PIVOT_LIFT}）。中心は必ず画面の中央に来るので、それをフロアへ
     * 下げた時点で車体はまるごと上半分へ移る。ここはその残りの詰めで、<b>大きくすると
     * 今度は上へ行きすぎて画面の外へ出る</b>。</p>
     *
     * <p><b>位置には効かせない</b>（視線だけを下げて車を画面の上へずらす。位置にまで効かせると車が中心へ戻る）。</p>
     */
    public static final float SHOWROOM_PITCH = 3.0F;

    /** 構えの切り替わりの速さ。1 ティックでこの割合だけ寄る。 */
    private static final float SHOWROOM_BLEND_PER_TICK = 0.30F;

    /** ドラッグ 1 画素あたり何度回すか。 */
    private static final float ORBIT_PER_PIXEL = 0.45F;

    /**
     * 見上げ／見下ろしの範囲 [度]。
     *
     * <p><b>下限はカメラが地面へ潜らないための蓋。</b>カメラは回転の中心から視線の逆へ置くので、
     * 見上げる（負の角度）ほど下がる。中心をフロアまで下げてある以上、少し見上げただけで
     * 地面を割るので、<b>角度の蓋だけでは足りない</b>——最後に位置の高さそのものも
     * 押し戻している（{@link #SHOWROOM_FLOOR_MARGIN}）。</p>
     */
    private static final float ORBIT_PITCH_MIN = -5.0F;
    private static final float ORBIT_PITCH_MAX = 50.0F;

    /**
     * 換装画面のあいだ、カメラをフロアからこれ以上は下げない [m]。
     *
     * <p>角度の蓋（{@link #ORBIT_PITCH_MIN}）だけに頼れないのは、<b>基準になる視線の角度が
     * プレイヤー側の向き次第</b>だから。最後に高さを押し戻せば、どんな向きから開いても潜らない。</p>
     */
    private static final double SHOWROOM_FLOOR_MARGIN = 0.20;

    /** ホイール 1 段あたりの寄り引き [ブロック] と、その範囲。 */
    private static final double ZOOM_STEP = 0.8;
    private static final double ZOOM_MIN = -4.0;
    private static final double ZOOM_MAX = 12.0;

    /**
     * 換装画面で回したぶん。<b>車を基準にした相対角なので、車が向きを変えても付いてくる。</b>
     *
     * <p>閉じても捨てない——{@link #showroom} が 0 へ寄るあいだに掛かる係数が
     * 0 になるので勝手に消え、開き直せば<b>さっき見ていた角度から始まる</b>。</p>
     */
    private static float orbitYaw;
    private static float orbitPitch;
    private static double orbitZoom;

    /**
     * いまどれだけショールームの構えに寄っているか（0..1）。
     *
     * <p><b>いきなり切り替えない。</b>画面を開いた瞬間にカメラが飛ぶと、何が起きたのか
     * 分からないうえ酔う。</p>
     */
    private static float showroom;

    private ShowroomCamera() {
    }

    /**
     * カメラの距離を刻む。負で近づき、正で遠ざかる。設定ファイルへそのまま書いて保存する。
     *
     * @return 新しい距離 [ブロック]
     */
    public static double stepDistance(int steps) {
        double distance = Mth.clamp(ClientConfig.cameraDistance + steps * DISTANCE_STEP,
                MIN_DISTANCE, MAX_DISTANCE);
        ClientConfig.cameraDistance = distance;
        ClientConfig.CAMERA_DISTANCE.set(distance);
        ClientConfig.SPEC.save();
        return distance;
    }

    /**
     * 車をいじる画面（換装・セッティング）で車のまわりを回す。ドラッグ 1 回ぶん。
     *
     * <p><b>プレイヤーの向きは触らない。</b>触ると {@code CarCamera} が「自分でマウスを
     * 動かした」と誤解して車への追従を止めてしまう（衝突の揺れと同じ理由）。</p>
     */
    public static void orbit(double dragX, double dragY) {
        // ±180 度に丸める。開閉のアニメーションはこの角度に割合を掛けるので、何周もためると開閉のたびに回る
        orbitYaw = Mth.wrapDegrees(orbitYaw + (float) dragX * ORBIT_PER_PIXEL);
        orbitPitch = Mth.clamp(orbitPitch + (float) dragY * ORBIT_PER_PIXEL,
                ORBIT_PITCH_MIN, ORBIT_PITCH_MAX);
    }

    /** 車をいじる画面での寄り引き。ホイール 1 段ぶん。 */
    public static void zoom(double delta) {
        orbitZoom = Mth.clamp(orbitZoom - delta * ZOOM_STEP, ZOOM_MIN, ZOOM_MAX);
    }

    public static float orbitYaw() {
        return orbitYaw;
    }

    public static float orbitPitch() {
        return orbitPitch;
    }

    /**
     * ショールームの構えへの寄り具合を進める。1 フレームに 1 回だけ呼ぶ（両方で進めると倍の速さで寄る）。
     *
     * <p>フレーム時間で刻むのは、カメラの見た目だけの話で決定性が要らないため
     * （物理は固定刻みのまま）。</p>
     *
     * @param deltaTicks 前のフレームからのティック数
     * @return 寄り具合 0..1
     */
    public static float update(float deltaTicks) {
        float target = ScreenStyle.isCarScreen() ? 1.0F : 0.0F;
        float rate = Mth.clamp(deltaTicks * SHOWROOM_BLEND_PER_TICK, 0.0F, 1.0F);
        showroom += (target - showroom) * rate;
        if (Math.abs(target - showroom) < 0.002F) {
            showroom = target;
        }
        return showroom;
    }

    /**
     * 回転の中心の高さ。普段は車の位置から {@code cameraHeight} 上、ショールームではフロアのすぐ上。
     *
     * @param bodyY  車の位置の Y（シャシー基準面）
     * @param floorY 車体メッシュの原点（フロア）の Y
     */
    public static double pivotY(double bodyY, double floorY, float lift) {
        return Mth.lerp(lift, bodyY + ClientConfig.cameraHeight, floorY + SHOWROOM_PIVOT_LIFT);
    }

    /**
     * 中心からカメラまでの距離（壁で縮める前）。
     *
     * @param speedRatio この車が出せる速度に対するいまの速度 0..1
     */
    public static double distance(double speedRatio, float lift) {
        double distance = ClientConfig.cameraDistance + ClientConfig.cameraSpeedPull * speedRatio;
        return Mth.lerp(lift, distance, Math.max(1.0, Math.max(distance, SHOWROOM_DISTANCE) + orbitZoom));
    }

    /**
     * 中心をフロアへ下げたぶん、少し見上げただけで地面を割る。最後に押し戻した高さ。
     * 狙いの点からわずかにずれるが、土に埋まるよりはよい。
     */
    public static double keepAboveFloor(double y, double floorY, float lift) {
        if (lift <= 0.0F) {
            return y;
        }
        return Math.max(y, Mth.lerp(lift, y, floorY + SHOWROOM_FLOOR_MARGIN));
    }
}
