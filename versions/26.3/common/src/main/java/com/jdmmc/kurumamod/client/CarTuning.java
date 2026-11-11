package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.physics.CarSpec;
import com.jdmmc.kurumamod.tuning.TunableParameter;

/**
 * 調整画面（{@link CarTuningScreen}）でいじった値を保持するクライアント側の入れ物。
 *
 * <p>物理を解くのは運転クライアントなので、ここの値を運転中の車へ流し込めばその場で挙動が変わる。
 * サーバーには {@link CarClientDriver} が変更を検知して送る（無人の車をサーバーが動かすときに
 * 車高がずれないようにするため）。</p>
 *
 * <p>持っているのは「いま調整している値」で、乗り換えると<b>その車の値を取り込む</b>
 * （{@link #adopt}）。逆に押し込んではいけない——カーパックの車に乗った瞬間、
 * 前の車の調整値で上書きされて別物になってしまう。</p>
 */
public final class CarTuning {

    private static CarSpec spec = CarSpec.DEFAULT;
    /**
     * 「戻す」で戻る先。<b>既定の車ではなく、いま乗っている車種の諸元。</b>
     *
     * <p>AE86 を調整して「戻す」を押したときに戻るのは AE86 の素の状態であって、
     * MOD 同梱の車ではない。</p>
     */
    private static CarSpec base = CarSpec.DEFAULT;
    /** 変更のたびに増える。同期済みかどうかの判定に使う。 */
    private static int revision;

    private CarTuning() {
    }

    public static CarSpec spec() {
        return spec;
    }

    /** 「戻す」で戻る先＝いま乗っている車種の素の諸元。 */
    public static CarSpec base() {
        return base;
    }

    /**
     * 乗った車の値を取り込む。
     *
     * @param current その車の今の諸元（前に乗ったときの調整が残っていることもある）
     * @param carType その車種の素の諸元。「戻す」の戻り先になる
     */
    public static void adopt(CarSpec current, CarSpec carType) {
        spec = current;
        base = carType;
        revision++;
    }

    public static int revision() {
        return revision;
    }

    /**
     * レース中に、決まり（レギュレーション）の外へ出る変更を通さない。
     *
     * <p><b>入口をここ 1 か所にしてある</b>——スライダー・項目ごとの R・すべて戻す・プリセットの
     * どれから来ても同じ判定に掛かる。通さなかったときはアクションバーで知らせる。</p>
     */
    private static boolean admit(CarSpec candidate) {
        if (RaceLobby.allowsTuning(spec, candidate)) {
            return true;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(
                    net.minecraft.network.chat.Component.translatable("race.kurumamod.tuning_blocked"));
        }
        return false;
    }

    /** 1 項目を表示単位の値で設定する。 */
    public static void set(TunableParameter parameter, double displayValue) {
        CarSpec.Builder builder = spec.toBuilder();
        parameter.applyDisplay(builder, displayValue);
        CarSpec next = builder.build();
        if (!admit(next)) {
            return;
        }
        spec = next;
        revision++;
    }

    /** 2 項目を一緒に設定する（鍵でつないだ行）。<b>判定は 2 つを動かしたあとの姿で 1 回だけ。</b> */
    public static void setPair(TunableParameter first, double firstValue,
                               TunableParameter second, double secondValue) {
        CarSpec.Builder builder = spec.toBuilder();
        first.applyDisplay(builder, firstValue);
        second.applyDisplay(builder, secondValue);
        CarSpec next = builder.build();
        if (!admit(next)) {
            return;
        }
        spec = next;
        revision++;
    }

    /** 1 項目を表示単位の値にしたときの諸元（設定はしない）。G 画面が決まりの範囲を測るのに使う。 */
    public static CarSpec preview(TunableParameter parameter, double displayValue) {
        CarSpec.Builder builder = spec.toBuilder();
        parameter.applyDisplay(builder, displayValue);
        return builder.build();
    }

    /**
     * 戻す（項目ごとの R・すべて戻す）は、<b>スタート前なら決まりの外になっても通す</b>。
     * 車種の値そのものが決まりを超えていることがあり、そのとき戻せないのでは困る。
     * 外れたら判定が違反になるので、本人が直す。スタート後は失格になるので止める。
     */
    private static boolean admitReset(CarSpec candidate) {
        return RaceLobby.allowsReset() || admit(candidate);
    }

    /** 1 項目だけ、いま乗っている車種の値に戻す。 */
    public static void resetParameter(TunableParameter parameter) {
        CarSpec.Builder builder = spec.toBuilder();
        parameter.applyInternal(builder, parameter.internalValue(base));
        CarSpec next = builder.build();
        if (!admitReset(next)) {
            return;
        }
        spec = next;
        revision++;
    }

    /**
     * 設定をまるごと差し替える。プリセットの読み込みで使う。
     *
     * <p>運転中なら次のティックで {@link CarClientDriver} が車へ流し込み、サーバーへも送る。</p>
     */
    public static void apply(CarSpec loaded) {
        if (!admit(loaded)) {
            return;
        }
        spec = loaded;
        revision++;
    }

    /** 全項目を、いま乗っている車種の値に戻す。 */
    public static void reset() {
        if (!admitReset(base)) {
            return;
        }
        spec = base;
        revision++;
    }
}
