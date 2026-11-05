package com.jdmmc.kurumamod.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 頂点を<b>塊で</b>渡す。Embeddium（1.20.1）/ Sodium（1.21.1）の {@code VertexBufferWriter} があれば使う。
 *
 * <h2>なぜ</h2>
 *
 * <p>シェーダーパック（Oculus / Iris）を使っていると、車がひどく重かった（11 台で 234 → 9 FPS）。
 * パックは頂点を 1 つ受け取るたびにパック用の情報（向きなど）を足して書き直すので、
 * {@code vertex().color()...endVertex()} で 1 頂点ずつ渡すと、その処理が頂点の数だけ走る。
 * 車は 1 台 2.5 万頂点あり、影の段と本描画で 2 回描かれる。</p>
 *
 * <p>Sodium 系は「同じ並びの頂点をまとめてメモリに置いて 1 回で渡す」口を持っていて、
 * パックはその塊をまとめて書き直せる（Sodium がバニラのモブを速くしているのと同じ道）。</p>
 *
 * <h2>Embeddium / Sodium にはコンパイル時に依存しない</h2>
 *
 * <p>実行時にクラスを探して、あれば使う。無い・書き込み先が対応していないときは false を返すので、
 * 呼ぶ側が 1 頂点ずつの描き方で描く。</p>
 *
 * <p>頂点の並びはバニラの {@code NEW_ENTITY}（Sodium の {@code ModelVertex} / {@code EntityVertex}）と同じ
 * 36 バイト: 位置 3 float・色 ABGR・UV 2 float・オーバーレイ・明るさ・法線（各 int）。</p>
 */
final class BulkVertexWriter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 1 頂点のバイト数。 */
    private static final int STRIDE = 36;
    /** 1 回に渡す頂点の数。{@code MemoryStack} は既定で 64KB しかないので、それに収まる量で区切る。 */
    private static final int BATCH = 1024;

    private static final MethodHandle TRY_OF;
    private static final MethodHandle PUSH;
    private static final Object FORMAT;

    static {
        MethodHandle tryOf = null;
        MethodHandle push = null;
        Object format = null;
        try {
            Class<?> writer = Class.forName("net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter");
            // Sodium 0.5 系（Embeddium）は ModelVertex、0.6 系は EntityVertex。並びは同じ
            Class<?> vertex = findClass("net.caffeinemc.mods.sodium.api.vertex.format.common.EntityVertex",
                    "net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex");
            if (vertex.getField("STRIDE").getInt(null) != STRIDE) {
                throw new IllegalStateException("頂点の大きさが想定と違う: " + vertex.getField("STRIDE").getInt(null));
            }
            format = vertex.getField("FORMAT").get(null);
            // 並びの型は版で違う（0.5 系は Sodium の VertexFormatDescription、0.6 系はバニラの VertexFormat）。
            // FORMAT の宣言された型をそのまま push の引数の型として使う
            Class<?> description = vertex.getField("FORMAT").getType();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            tryOf = lookup.findStatic(writer, "tryOf", MethodType.methodType(writer, VertexConsumer.class))
                    .asType(MethodType.methodType(Object.class, VertexConsumer.class));
            push = lookup.findVirtual(writer, "push",
                            MethodType.methodType(void.class, MemoryStack.class, long.class, int.class, description))
                    .asType(MethodType.methodType(void.class, Object.class, MemoryStack.class, long.class, int.class,
                            Object.class));
            LOGGER.info("頂点を塊で渡す（Sodium 系の VertexBufferWriter を使う）");
        } catch (ClassNotFoundException e) {
            // Embeddium / Sodium が入っていない。1 頂点ずつの描き方で描く
            tryOf = null;
            push = null;
        } catch (Throwable e) {
            LOGGER.warn("Sodium 系の VertexBufferWriter を使えない。1 頂点ずつ渡す", e);
            tryOf = null;
            push = null;
        }
        TRY_OF = tryOf;
        PUSH = push;
        FORMAT = format;
    }

    private BulkVertexWriter() {
    }

    private static Class<?> findClass(String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // 次の名前を試す
            }
        }
        throw new ClassNotFoundException(String.join(" / ", names));
    }

    /**
     * {@link ObjModel} の面をまとめて書く。
     *
     * @param vertices {@link ObjModel} の頂点配列（1 頂点 {@code stride} 個の float）
     * @param faces    描く面の番号
     * @return 書けたら true。false なら呼ぶ側が 1 頂点ずつ描く
     */
    static boolean write(VertexConsumer consumer, float[] vertices, int[] faces, int faceFloats, int stride,
                         int alphaOffset, Matrix4f matrix, Matrix3f normal, float flip,
                         int packedLight, int packedOverlay,
                         float tintRed, float tintGreen, float tintBlue, float alpha) {
        if (TRY_OF == null || faces.length == 0) {
            return false;
        }
        Object writer;
        try {
            writer = TRY_OF.invokeExact(consumer);
        } catch (Throwable e) {
            return false;
        }
        if (writer == null) {
            return false;
        }

        int total = faces.length * 4;
        int written = 0;
        int faceIndex = 0;
        int corner = 0;
        while (written < total) {
            int count = Math.min(BATCH, total - written);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long start = stack.nmalloc(count * STRIDE);
                long ptr = start;
                for (int i = 0; i < count; i++) {
                    int offset = faces[faceIndex] * faceFloats + corner * stride;
                    float x = flip * vertices[offset];
                    float y = vertices[offset + 1];
                    float z = vertices[offset + 2];
                    float nx = flip * vertices[offset + 3];
                    float ny = vertices[offset + 4];
                    float nz = vertices[offset + 5];

                    MemoryUtil.memPutFloat(ptr, matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30());
                    MemoryUtil.memPutFloat(ptr + 4, matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31());
                    MemoryUtil.memPutFloat(ptr + 8, matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32());
                    MemoryUtil.memPutInt(ptr + 12, abgr(vertices[offset + 8] * tintRed, vertices[offset + 9] * tintGreen,
                            vertices[offset + 10] * tintBlue, vertices[offset + alphaOffset] * alpha));
                    MemoryUtil.memPutFloat(ptr + 16, vertices[offset + 6]);
                    MemoryUtil.memPutFloat(ptr + 20, vertices[offset + 7]);
                    MemoryUtil.memPutInt(ptr + 24, packedOverlay);
                    MemoryUtil.memPutInt(ptr + 28, packedLight);
                    MemoryUtil.memPutInt(ptr + 32, normal(
                            normal.m00() * nx + normal.m10() * ny + normal.m20() * nz,
                            normal.m01() * nx + normal.m11() * ny + normal.m21() * nz,
                            normal.m02() * nx + normal.m12() * ny + normal.m22() * nz));
                    ptr += STRIDE;

                    if (++corner == 4) {
                        corner = 0;
                        faceIndex++;
                    }
                }
                PUSH.invokeExact(writer, stack, start, count, FORMAT);
            } catch (Throwable e) {
                // 途中で失敗すると、それまでに渡した分だけ描かれて半端になる。起きたら以後は使わない
                LOGGER.error("頂点を塊で渡せなかった", e);
                return written > 0;
            }
            written += count;
        }
        return true;
    }

    /** 色を ABGR の int に詰める（メモリ上は R, G, B, A の順のバイトになる）。 */
    private static int abgr(float r, float g, float b, float a) {
        return (byteOf(a) << 24) | (byteOf(b) << 16) | (byteOf(g) << 8) | byteOf(r);
    }

    private static int byteOf(float value) {
        return (int) (Math.max(0.0F, Math.min(1.0F, value)) * 255.0F) & 0xFF;
    }

    /** 法線を符号付き 8 ビット 3 つに詰める（バニラの {@code normal()} と同じく正規化はしない）。 */
    private static int normal(float x, float y, float z) {
        return (signedByte(z) << 16) | (signedByte(y) << 8) | signedByte(x);
    }

    private static int signedByte(float value) {
        return ((int) (Math.max(-1.0F, Math.min(1.0F, value)) * 127.0F)) & 0xFF;
    }
}
