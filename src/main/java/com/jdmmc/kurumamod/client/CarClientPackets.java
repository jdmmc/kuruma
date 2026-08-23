package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CourseLinesPacket;
import com.jdmmc.kurumamod.network.RaceEventPacket;
import com.jdmmc.kurumamod.network.RaceStandingsPacket;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTabs;

import java.util.List;

/**
 * サーバーから届いたパケットのクライアント側の処理。
 *
 * <p>{@code net.minecraft.client.*} を参照するので、専用サーバーでは絶対に読み込まれないよう
 * {@code DistExecutor} 越しにだけ呼ぶこと。</p>
 */
public final class CarClientPackets {

    private CarClientPackets() {
    }

    /** レース中の順位表を受け取る。 */
    public static void applyStandings(RaceStandingsPacket packet) {
        RaceHud.acceptStandings(packet);
    }

    /** コースの線を受け取る。ゲートとして描く。 */
    public static void applyCourseLines(CourseLinesPacket packet) {
        CourseRenderer.accept(packet);
    }

    /** ラップ計測とスタート信号の合図を受け取る。 */
    public static void applyRaceEvent(RaceEventPacket packet) {
        RaceHud.accept(packet);
    }

    /**
     * 衝突の合図を受け取る。
     *
     * <p><b>運転している本人のぶんは捨てる。</b>本人は物理を解いた時点で自分の画面に
     * 鳴らしているので、中継されて戻ってきたものを鳴らすと二重になる。</p>
     */
    public static void applyImpact(int entityId, float impact) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        if (entity instanceof CarEntity car && !car.isControlledByLocalInstance()) {
            CarImpact.onImpact(car, impact);
        }
    }

    /**
     * ぶつけられた側の車を弾く。
     *
     * <p>物理を解いているのは<b>その車を運転している自分</b>だけなので、
     * {@code applyPush} 側で手元の車かどうかを見て弾く。関係ない車のぶんが届いても捨てる。</p>
     */
    public static void applyPush(int entityId, float dx, float dz, float dYawRate) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        if (entity instanceof CarEntity car) {
            car.applyPush(dx, dz, dYawRate);
        }
    }

    /**
     * 読み込まれている車種の一覧を受け取る。
     *
     * <p>受け取ったらクリエイティブタブを組み直す。<b>タブの中身はログインより前に
     * 作られていることがある</b>ので、後から届いた車種はそのままでは並ばない。</p>
     */
    public static void applyCarTypes(List<CarType> types) {
        CarTypes.replaceAll(types);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            CreativeModeTabs.tryRebuildTabContents(
                    minecraft.player.connection.enabledFeatures(),
                    minecraft.player.canUseGameMasterBlocks(),
                    minecraft.player.level().registryAccess());
        }
    }

    /**
     * 選べる部品の一覧を受け取る。
     *
     * <p>車種と違ってクリエイティブタブには並ばないので、入れ替えるだけでよい。
     * 換装画面（{@code CarPartScreen}）は開くたびにここから引く。</p>
     */
    public static void applyCarParts(List<CarPart> parts) {
        CarParts.replaceAll(parts);
    }

    /**
     * 車 1 台の装着状態を受け取る。
     *
     * <p><b>運転している本人のぶんも捨てない。</b>諸元は調整画面が正なので送り返された
     * ぶんを捨てるが、装着状態を決めるのは<b>サーバー</b>（部品の一覧と突き合わせて弾く）。
     * 届いたものが正なので、そのまま入れる。</p>
     */
    public static void applyParts(int entityId, PartFitment parts) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        if (entity instanceof CarEntity car) {
            car.setFitment(parts);
        }
    }

    /** 車の諸元を受け取る。 */
    public static void applySpec(int entityId, CarSpec spec) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Entity entity = Minecraft.getInstance().level.getEntity(entityId);
        if (entity instanceof CarEntity car && !car.isControlledByLocalInstance()) {
            // 運転している本人は自分の調整画面が正なので、送り返されたぶんは捨てる
            car.setSpec(spec);
        }
    }
}
