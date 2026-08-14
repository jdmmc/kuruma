package com.jdmmc.kurumamod.network;

import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.physics.Wheel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 運転クライアント → サーバー。位置・向き以外の車両状態を送る。
 *
 * <p>受け取ったサーバーは値を検算せず {@link net.minecraft.network.syncher.SynchedEntityData} に流し込むだけで、
 * そこから先はバニラの同期に任せて周囲のクライアントへ配る。</p>
 *
 * <p>運ぶのは<b>見た目に効くものだけ</b>。姿勢（ピッチ・ロール）とタイヤ（切れ角・サスの伸縮・回転）が
 * それにあたる。</p>
 *
 * <p><b>タイヤの回転を車速から作ってはいけない。</b>車速から積むと、どの輪も路面と同じ速さで
 * 転がっていることになるので、<b>空転もロックも他人の画面では見えない</b>。輪ごとの角速度を
 * そのまま配る。</p>
 *
 * @param entityId   対象の車のエンティティ ID
 * @param steerAngle 前輪の切れ角 [rad]
 * @param speed      車速 [m/s]
 * @param yaw        ヨー角 [度]。バニラの位置パケットは 1.4 度刻みに量子化するので、描画用に別途配る
 * @param pitch      ピッチ角 [rad]。正で鼻上げ
 * @param roll       ロール角 [rad]。正で右下がり
 * @param suspension 各輪のサスペンション長 [m]。{@link Wheel} の並び
 * @param wheelSpeed 各輪の回転角速度 [rad/s]。{@link Wheel} の並び
 * @param friction   各輪が接地面で捨てている摩擦の仕事率 [W]。{@link Wheel} の並び。煙の濃さに使う
 * @param rpm        エンジン回転数。排気音のピッチに使う
 * @param gear       今の段。負で後退、0 で変速中。変速音に使う
 * @param throttle   アクセルの踏み込み量 0..1。排気音の音量に使う
 * @param slipAngle  車体スリップ角 [度]。タイヤの悲鳴に使う
 * @param surface    路面の種類（{@code RoadSurface} の並び順）。ロードノイズに使う
 * @param lights     灯火の状態のビット（ヘッドライト・ブレーキ）。バックは段から分かるので入れない
 */
public record CarStatePacket(int entityId, float steerAngle, float speed, float yaw,
                             float pitch, float roll,
                             float[] suspension, float[] wheelSpeed, float[] friction,
                             float rpm, int gear, float throttle, float slipAngle, int surface,
                             byte lights) {

    public CarStatePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(),
                readWheels(buf), readWheels(buf), readWheels(buf),
                buf.readFloat(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readByte(),
                buf.readByte());
    }

    private static float[] readWheels(FriendlyByteBuf buf) {
        float[] values = new float[Wheel.COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = buf.readFloat();
        }
        return values;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(steerAngle);
        buf.writeFloat(speed);
        buf.writeFloat(yaw);
        buf.writeFloat(pitch);
        buf.writeFloat(roll);
        for (int i = 0; i < Wheel.COUNT; i++) {
            buf.writeFloat(suspension[i]);
        }
        for (int i = 0; i < Wheel.COUNT; i++) {
            buf.writeFloat(wheelSpeed[i]);
        }
        for (int i = 0; i < Wheel.COUNT; i++) {
            buf.writeFloat(friction[i]);
        }
        buf.writeFloat(rpm);
        buf.writeByte(gear);
        buf.writeFloat(throttle);
        buf.writeFloat(slipAngle);
        buf.writeByte(surface);
        buf.writeByte(lights);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) return;

            Entity entity = sender.level().getEntity(entityId);
            // 運転している本人からのパケットだけ受け付ける
            if (entity instanceof CarEntity car && car.getControllingPassenger() == sender) {
                car.applyDriverState(steerAngle, speed, yaw, pitch, roll, suspension, wheelSpeed, friction,
                        rpm, gear, throttle, slipAngle, surface, lights);
            }
        });
        context.setPacketHandled(true);
    }
}
