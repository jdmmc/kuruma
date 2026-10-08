package com.jdmmc.kurumamod.physics;

/**
 * 付けている空力部品が足すダウンフォースの面積 [m²]（軸ごと、角度を決めた後の値）。
 *
 * <p>{@link Turbo} や {@link Tire} と同じく<b>部品から来る</b>もので、{@code Tunables} には無い
 * （保存も同期もされず、{@code CarEntity} が装着状態から毎回載せ直す）。
 * 調整画面のダウンフォース（車体そのもののぶん）とは別に持ち、{@link CarSpec} が足し合わせる。
 * <b>調整画面の値へ焼き込んではいけない。</b>回収するたびに積み重なり、調整画面にも漏れる。</p>
 */
public record PartAero(double front, double rear) {

    /** 空力部品が何も付いていない。 */
    public static final PartAero NONE = new PartAero(0.0, 0.0);

    public PartAero {
        front = Aero.clamp(front, 0.0, Aero.MAX_AREA * 2.0);
        rear = Aero.clamp(rear, 0.0, Aero.MAX_AREA * 2.0);
    }

    /** 2 つの部品のぶんを足す（前と後ろに 1 つずつ付けたとき）。 */
    public PartAero plus(PartAero other) {
        return new PartAero(front + other.front, rear + other.rear);
    }
}
