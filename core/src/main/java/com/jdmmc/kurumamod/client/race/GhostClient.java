package com.jdmmc.kurumamod.client.race;

import com.jdmmc.kurumamod.race.RaceText;

import java.nio.file.Path;

/**
 * ゴースト（{@link GhostEngine}）が Minecraft に触る口。版ごとの {@code CarGhosts} が中身を持ち、{@link #set} で置く。
 *
 * <p>車の見た目（車種・諸元・装着）は版の側のもので、core からは中身を見ない（{@code Object look}。
 * 版の {@code CompoundTag} や記録そのもの）。</p>
 */
public interface GhostClient {

    static GhostClient get() {
        return Holder.client;
    }

    static void set(GhostClient client) {
        Holder.client = client;
    }

    final class Holder {
        private static GhostClient client;

        private Holder() {
        }
    }

    /** 運転している車。 */
    interface Car {
        /** 同じ車か見分けるためのもの（降りた・乗り換えたを知る）。 */
        Object identity();

        int entityId();

        /** 自分のほかに乗っている人の数。 */
        int riders();

        /** このティックにサーバーへ送った状態。まだ送っていなければ null。 */
        GhostFrame lastSentFrame();

        /** 記録に残す見た目（車種・諸元・装着）。 */
        Object look();

        /** 車がいる次元の名前。 */
        String dimension();
    }

    /** 自分の画面にだけいる、ゴーストの車。 */
    interface Ghost {
        /** まだ出ているか（消された・別のワールドへ移った、なら false）。 */
        boolean alive();

        void play(GhostFrame frame);

        void discard();
    }

    /** ファイルに置いてある 1 本（まだコマを読み解く前）。 */
    record Stored(Object look, String dimension, long lapMillis, int frames, byte[] data, int version) {
    }

    boolean isPaused();

    /** ワールドに入っているか。 */
    boolean inWorld();

    /** H メニューの「ゴースト」が入っているか。 */
    boolean ghostsEnabled();

    /** 運転している車。運転していなければ null。 */
    Car drivenCar();

    /** 乗っている乗り物のエンティティ id。乗っていなければ -1。 */
    int vehicleId();

    /** ゴーストの車を出す。 */
    Ghost spawn(Object look, int entityId, GhostFrame first);

    /**
     * 同乗者へ送る見た目（{@code GhostRidePacket#look}）。{@code look} は記録の見た目。
     */
    Object rideLook(Object look);

    /** 同乗者へ送られてきた見た目から、ゴーストの車を出す。読めなければ null。 */
    Ghost spawnFromRideLook(Object rideLook, int entityId, GhostFrame first);

    void sendRideLook(int carEntityId, Object rideLook);

    void sendRideFrame(int carEntityId, GhostFrame frame);

    void sendRideStop(int carEntityId);

    /** 接続しているか（送れるか）。 */
    boolean connected();

    /** いる次元の名前。ワールドに入っていなければ null。 */
    String currentDimension();

    /** シングルプレイのワールドのセーブのフォルダ。マルチなら null。 */
    Path singleplayerWorld();

    /** マルチプレイの接続先のアドレス。シングルか、接続していなければ null。 */
    String serverAddress();

    /** 自分の UUID（文字列）。いなければ null。 */
    String playerId();

    Path configDir();

    boolean save(Path path, Object look, String dimension, long lapMillis, int frames, byte[] data);

    /** 読めなければ例外。無ければ呼ばない。 */
    Stored load(Path path) throws java.io.IOException;

    /** チャットに出す（アクションバーではなく）。 */
    void message(RaceText text);
}
