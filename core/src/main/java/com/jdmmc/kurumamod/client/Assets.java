package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarId;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * リソースパック（{@code assets/}）のファイルを開く口。バニラの {@code ResourceManager#getResource} の代わり。
 * 版ごとの {@code McAssets} が中身を持ち、クライアントの起動時に {@link #set} で置く。
 *
 * <p>名前はバニラに揃えてある（{@link #getResource}・{@link Asset#openAsReader}・{@link Asset#open}）。</p>
 */
public interface Assets {

    /** ファイル 1 つ。 */
    interface Asset {
        InputStream open() throws IOException;

        BufferedReader openAsReader() throws IOException;
    }

    /** そのファイル。無ければ空。 */
    Optional<Asset> getResource(CarId location);

    /** {@code assets/<ns>/<directory>/} の下のファイルで、名前が {@code filter} に合うもの（バニラの {@code listResources}）。 */
    Map<CarId, Asset> listResources(String directory, Predicate<CarId> filter);

    static Assets get() {
        return Holder.assets;
    }

    static void set(Assets assets) {
        Holder.assets = assets;
    }

    final class Holder {
        private static Assets assets;

        private Holder() {
        }
    }
}
