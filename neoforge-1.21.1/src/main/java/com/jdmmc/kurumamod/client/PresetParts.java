package com.jdmmc.kurumamod.client;

import net.neoforged.neoforge.network.PacketDistributor;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.network.CarPartRequestPacket;
import com.jdmmc.kurumamod.part.CarPart;
import com.jdmmc.kurumamod.part.CarParts;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.part.PartSlot;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

/**
 * プリセットに入れるパーツ。保存するときに車から取り、読み込むときに換装を要求する。
 *
 * <p><b>読み込んでも手元では付け替えない。</b>装着状態を決めるのはサーバーなので、
 * 換装画面と同じく {@link CarPartRequestPacket} を場所ごとに送り、返ってきた
 * {@code CarPartsPacket} で車が変わるのを待つ。</p>
 */
public final class PresetParts {

    private PresetParts() {
    }

    /**
     * 保存するときの装着状態。乗っている車のもの（助手席でも取れる）。
     *
     * @return 乗っていなければ null（＝プリセットにパーツを含めない）
     */
    @Nullable
    public static PartFitment current() {
        CarEntity car = CarClientDriver.ridingCar(Minecraft.getInstance());
        return car == null ? null : car.getFitment();
    }

    /**
     * プリセットの装着状態へ付け替えるよう要求する。
     *
     * <p><b>プリセットに書かれていない場所は純正へ戻す。</b>パーツを含むプリセットは
     * 「この車の姿まるごと」なので、前の車のターボが残っていると同じ車にならない。</p>
     *
     * <p><b>この車に付かない部品の場所だけは、今のまま触らない。</b>そのまま送るとサーバーが
     * 要求ごと弾くので結果は同じだが、角度まで一緒に捨てられることになる。</p>
     *
     * <p>送る順は {@link PartSlot#VALUES}（ターボがブローオフより先）。</p>
     *
     * @return 付けられずに飛ばした場所の数。運転していなければ -1
     */
    public static int apply(PartFitment parts) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!CarClientDriver.isDriver(minecraft)) {
            return -1;
        }
        CarEntity car = CarClientDriver.ridingCar(minecraft);
        int skipped = 0;
        for (PartSlot slot : PartSlot.VALUES) {
            PartFitment.Setting wanted = parts.get(slot);
            if (wanted.equals(car.getFitment().get(slot))) {
                continue;
            }
            if (PartFitment.isNone(wanted.partId())) {
                // 「なし」は外せる初期装備のある場所でだけ通る。それ以外ではサーバーが弾くか純正に読み替える
                var defaults = com.jdmmc.kurumamod.car.CarTypes.get(car.getCarId()).defaultPart(slot);
                if (defaults != null && !defaults.removable()) {
                    skipped++;
                    continue;
                }
            } else if (wanted.partId() != null) {
                CarPart part = CarParts.get(wanted.partId());
                if (part == null || part.slot() != slot || !part.fits(car.getCarId())) {
                    skipped++;
                    continue;
                }
            }
            PacketDistributor.sendToServer(new CarPartRequestPacket(car.getId(), slot, wanted));
        }
        return skipped;
    }
}
