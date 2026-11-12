package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;

/**
 * サーバー → 周囲のクライアント。車の諸元を配る。
 *
 * <p>諸元は見た目に効く。ホイールベースは拡大率に、ストロークとバネはタイヤの位置に効くので、
 * <b>配らないと他人の車だけ既定の寸法で描かれる</b>。</p>
 *
 * <p>車がクライアントに現れたときは {@code IEntityWithComplexSpawn} が同じ内容を運ぶ。
 * こちらは<b>運転者が走行中に調整画面をいじったとき</b>のためにある。</p>
 *
 * @param entityId 対象の車のエンティティ ID
 * @param spec     配る諸元
 */
public record CarSpecPacket(int entityId, CarSpec spec) implements RacePayload {

    public CarSpecPacket(RaceBuf buf) {
        this(buf.readVarInt(), CarSpecCodec.read(buf));
    }

    public void encode(RaceBuf buf) {
        buf.writeVarInt(entityId);
        CarSpecCodec.write(buf, spec);
    }

}
