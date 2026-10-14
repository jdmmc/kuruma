package com.jdmmc.kurumamod.client;

/**
 * ハンドルのどこを左右の手で握っているか。<b>送りハンドル</b>（持ち替え）を含む。Minecraft に依存しない。
 *
 * <p>角度はすべて「運転者から見て、真上から時計回り」の度。右手の定位置が 3 時（+90）、左手が 9 時（-90）。
 * 手はリムを掴んでいる間はハンドルと一緒に回り、回れる範囲（右手は {@link #REACH_LOW}〜{@link #REACH_HIGH}）を越えたら離して、
 * 反対寄りの位置を掴み直す（宙を {@link #REGRAB_SECONDS} かけて移る）。<b>片手は必ず握ったまま</b>にする——
 * 両手が同時に離れると、ハンドルが手を離れて回っているように見える。掴み直せないまま範囲を越えた手は、
 * その場に留まってリムを手の中で滑らせる（速く回すと手が追いつかないのは実車でも同じ）。</p>
 *
 * <p>ハンドルがまっすぐに戻ってゆっくりになったら、定位置から外れている手を 1 本ずつ戻す。</p>
 *
 * <p><b>舵を放している間（A/D を押していない）は手を離している。</b>手は水平に持った位置（3 時と 9 時）へ戻って
 * そこで止まり、戻っていくハンドルのリムが手の下を滑る。両手とも握っていないので、一緒に動かしてよい。
 * 次に舵を入れたら、その位置を掴む。</p>
 */
public final class SteeringGrip {

    /** 右手の定位置（3 時）[度]。左手は符号を返す。 */
    public static final double REST = 90.0;
    /**
     * 右手が握ったまま回れる範囲 [度]。左手は符号を返して入れ替える（[-200, 30]）。
     * 上は頂点を少し越えて反対側まで、下は真下を少し越えるところまで。
     */
    private static final double REACH_LOW = -30.0;
    private static final double REACH_HIGH = 200.0;
    /** 範囲を越えた右手が掴み直す位置 [度]。時計回りで下へ抜けたら上の側を、反時計回りで上へ抜けたら下の側を */
    private static final double REGRAB_AFTER_HIGH = 60.0;
    private static final double REGRAB_AFTER_LOW = 130.0;
    /** 掴み直すのにかける時間 [s] */
    public static final double REGRAB_SECONDS = 0.18;
    /** 定位置へ戻すのは、ハンドルの回る速さがこれ以下で [度/s]… */
    private static final double SETTLE_RATE = 25.0;
    /** …ハンドルがこれより中立に近く [度]… */
    private static final double SETTLE_WHEEL = 30.0;
    /** …手が定位置からこれ以上ずれているとき [度] */
    private static final double SETTLE_OFFSET = 20.0;
    /**
     * …握ってからこれだけ経っていれば [s]。掴み直した瞬間はまだ回り出していないので、
     * これが無いと切りはじめに手が定位置へ戻りに行く
     */
    private static final double SETTLE_HELD = 0.4;

    /** 片手ぶん。 */
    private static final class Hand {
        /** 右手なら 1、左手なら -1。範囲と定位置の符号を返すのに使う */
        final double side;
        /** 掴んでいるリムの位置（ハンドルに対する角度）[度]。宙を移っている間は掴み直す先 */
        double anchor;
        /** 宙を移っている残りの割合。0 なら握っている */
        double flight;
        /** 宙を移りはじめたときの位置（ハンドルに対する角度ではなく、見た目の角度）[度] */
        double flightFrom;

        Hand(double side) {
            this.side = side;
            this.anchor = side * REST;
        }
    }

    private final Hand right = new Hand(1.0);
    private final Hand left = new Hand(-1.0);
    private double wheel;
    private double lastWheel;
    private boolean started;
    private double rate;
    /** 舵を握り続けている時間 [s]。放すと 0 */
    private double heldSeconds;

    /** 乗り直したときなど。両手を定位置で握らせ直す。 */
    public void reset(double wheelAngle) {
        for (Hand hand : new Hand[]{right, left}) {
            hand.anchor = hand.side * REST - wheelAngle;
            hand.flight = 0.0;
        }
        wheel = wheelAngle;
        lastWheel = wheelAngle;
        rate = 0.0;
        started = true;
    }

    /** 舵を握っているものとして進める。 */
    public void update(double wheelAngle, double dt) {
        update(wheelAngle, dt, true);
    }

    /**
     * 進める。
     *
     * @param wheelAngle ハンドルの回転角 [度]。正で右へ切る（時計回り）
     * @param dt         前回からの時間 [s]
     * @param held       舵を操作しているか。false なら手を離していて、手は動かない
     */
    public void update(double wheelAngle, double dt, boolean held) {
        if (!started) {
            reset(wheelAngle);
            return;
        }
        if (dt <= 0.0) {
            return;
        }
        double turned = wheelAngle - lastWheel;
        wheel = wheelAngle;
        // 速さは軽く均す（フレームの間隔で数字が暴れないように）
        double raw = turned / dt;
        rate += Math.min(1.0, dt / 0.1) * (raw - rate);
        lastWheel = wheel;

        heldSeconds = held ? heldSeconds + dt : 0.0;
        if (!held) {
            // 手を離している。手は水平に持った位置へ戻ってそこで止まり、リムが手の下を滑る
            // （ずらすのは掴んでいる位置のほうで、見た目の角度は変わらない）
            for (Hand hand : new Hand[]{right, left}) {
                hand.anchor -= turned;
                if (hand.flight > 0.0) {
                    hand.flight = Math.max(0.0, hand.flight - dt / REGRAB_SECONDS);
                } else if (Math.abs(hand.anchor + wheel - hand.side * REST) > 1.0) {
                    regrab(hand, hand.side * REST);
                }
            }
            return;
        }

        for (Hand hand : new Hand[]{right, left}) {
            if (hand.flight > 0.0) {
                hand.flight = Math.max(0.0, hand.flight - dt / REGRAB_SECONDS);
            }
        }
        for (Hand hand : new Hand[]{right, left}) {
            Hand other = hand == right ? left : right;
            if (hand.flight > 0.0 || other.flight > 0.0) {
                continue;
            }
            // 自分の側から見た角度（右手ならそのまま、左手は左右を返す）で範囲を見る
            double own = hand.side * (hand.anchor + wheel);
            if (own > REACH_HIGH) {
                regrab(hand, hand.side * REGRAB_AFTER_HIGH);
            } else if (own < REACH_LOW) {
                regrab(hand, hand.side * REGRAB_AFTER_LOW);
            }
        }
        // 範囲を越えたのに掴み直せない手（もう片方が宙にいる）は、その場に留まってリムを手の中で滑らせる。
        // 張り付いたまま回すと、キーボードの速さ（毎秒 900 度）では片手がハンドルと一緒に 3 周した。
        // 掴み直しに向かっている先も同じ範囲に収める（宙にいる間にもハンドルは回る）
        for (Hand hand : new Hand[]{right, left}) {
            double own = hand.side * (hand.anchor + wheel);
            double clamped = Math.max(REACH_LOW, Math.min(REACH_HIGH, own));
            if (clamped != own) {
                hand.anchor = hand.side * clamped - wheel;
            }
        }
        // まっすぐに戻ってゆっくりになったら、定位置から外れた手を 1 本ずつ戻す
        if (heldSeconds > SETTLE_HELD && Math.abs(rate) < SETTLE_RATE && Math.abs(wheel) < SETTLE_WHEEL
                && right.flight <= 0.0 && left.flight <= 0.0) {
            for (Hand hand : new Hand[]{right, left}) {
                if (Math.abs(hand.anchor + wheel - hand.side * REST) > SETTLE_OFFSET) {
                    regrab(hand, hand.side * REST);
                    break;
                }
            }
        }
    }

    /** 離して、見た目の角度 {@code target} の位置を掴み直しに行く。 */
    private void regrab(Hand hand, double target) {
        hand.flightFrom = hand.anchor + wheel;
        hand.anchor = target - wheel;
        hand.flight = 1.0;
    }

    /** 手の位置（見た目の角度。真上から時計回り）[度]。 */
    public double angle(boolean rightHand) {
        Hand hand = rightHand ? right : left;
        double grip = hand.anchor + wheel;
        if (hand.flight <= 0.0) {
            return grip;
        }
        double t = smooth(1.0 - hand.flight);
        return hand.flightFrom + (grip - hand.flightFrom) * t;
    }

    /** リムから浮かせている割合 0〜1（宙を移っている途中で 1 になる山）。 */
    public double lift(boolean rightHand) {
        Hand hand = rightHand ? right : left;
        return hand.flight <= 0.0 ? 0.0 : Math.sin(Math.PI * (1.0 - hand.flight));
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
