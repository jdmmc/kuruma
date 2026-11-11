package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.physics.Wheel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * ゴーストの 1 ティックぶん。位置と、その瞬間に運転者がサーバーへ送った状態（版の {@code CarStatePacket} と同じ中身）。
 *
 * <p>ファイルへの書き方（{@link #write}・{@link #read}）は 2.0.0 からのゴーストのファイルと同じ並び。変えると
 * 手元のゴーストが読めなくなる。</p>
 */
public record GhostFrame(double x, double y, double z, float steerAngle, float speed, float yaw, float pitch,
                         float roll, float[] suspension, float[] wheelSpeed, float[] friction, float rpm, int gear,
                         float throttle, float slipAngle, int surface, byte lights, float boost) {

    void write(DataOutputStream out) throws IOException {
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(steerAngle);
        out.writeFloat(speed);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeFloat(roll);
        for (int i = 0; i < Wheel.COUNT; i++) {
            out.writeFloat(suspension[i]);
            out.writeFloat(wheelSpeed[i]);
            out.writeFloat(friction[i]);
        }
        out.writeFloat(rpm);
        out.writeByte(gear);
        out.writeFloat(throttle);
        out.writeFloat(slipAngle);
        out.writeShort(surface);
        out.writeByte(lights);
        out.writeFloat(boost);
    }

    static GhostFrame read(DataInputStream in) throws IOException {
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float steer = in.readFloat();
        float speed = in.readFloat();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        float roll = in.readFloat();
        float[] suspension = new float[Wheel.COUNT];
        float[] wheelSpeed = new float[Wheel.COUNT];
        float[] friction = new float[Wheel.COUNT];
        for (int i = 0; i < Wheel.COUNT; i++) {
            suspension[i] = in.readFloat();
            wheelSpeed[i] = in.readFloat();
            friction[i] = in.readFloat();
        }
        float rpm = in.readFloat();
        int gear = in.readByte();
        float throttle = in.readFloat();
        float slip = in.readFloat();
        int surface = in.readUnsignedShort();
        byte lights = in.readByte();
        float boost = in.readFloat();
        return new GhostFrame(x, y, z, steer, speed, yaw, pitch, roll, suspension, wheelSpeed, friction, rpm, gear,
                throttle, slip, surface, lights, boost);
    }
}
