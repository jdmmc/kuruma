package com.jdmmc.kurumamod.car;

/**
 * {@link CarCore} が、それを持つ版の {@code CarEntity} に聞くこと。
 *
 * <p>版ごとに Minecraft のメソッドの名前が違う（1.21.1 の {@code isControlledByLocalInstance} が 26.3 では
 * {@code isLocalInstanceAuthoritative}）ので、その違いはここの実装で吸収する。</p>
 *
 * <p><b>Minecraft と同じ名前のメソッドを置かないこと。</b>開発環境ではバニラから継いだメソッドで満たされても、
 * 配布環境（Fabric の intermediary・Forge の SRG）では名前が変わって {@code AbstractMethodError} になる。</p>
 */
public interface CarHost {

    /** このクライアントが物理を解いている車か（クライアント側で、運転しているのがこのクライアント）。 */
    boolean simulatedOnThisClient();

    /**
     * この側（クライアントでもサーバーでも）が物理を解いているか。
     * 運転者のいない車はサーバーが解くので、サーバー側では true になりうる。
     */
    boolean simulatedHere();

    /** 向き [度]（Minecraft のヨー）。 */
    float vanillaYaw();

    /** 同期した値。運転していない側はここから読む。 */
    CarSync synced();
}
