package com.jdmmc.kurumamod.client;

/**
 * 画面の好みの設定（{@code kurumamod-client.toml}。版の {@code ClientConfig}）を、core の画面が読み書きする口。
 * {@code ConfigClientOptions} が中身を持ち、クライアントの起動時に {@link #set} で置く。
 *
 * <p>値は {@code ClientConfig} の項目の名前（{@code SHOW_HELP}・{@code HEADLIGHT_AIM} など）で引く。書き換えたら
 * {@link #refresh} で効かせ、ファイルへ書くのは画面を閉じるとき（{@link #save}）。</p>
 */
public interface ClientOptions {

    /** 設定 1 つ。 */
    interface Value<T> {
        T get();

        void set(T value);

        T getDefault();
    }

    /** 数の設定。範囲つき。 */
    interface Number extends Value<Double> {
        double min();

        double max();
    }

    Value<Boolean> bool(String name);

    Number number(String name);

    <E extends Enum<E>> Value<E> choice(String name, Class<E> type);

    /** 書き換えた値を、実際に読まれている static の値へ写す（{@code ClientConfig.refresh}）。 */
    void refresh();

    /** ファイルへ書く。 */
    void save();

    static ClientOptions get() {
        return Holder.options;
    }

    static void set(ClientOptions options) {
        Holder.options = options;
    }

    final class Holder {
        private static ClientOptions options;

        private Holder() {
        }
    }
}
