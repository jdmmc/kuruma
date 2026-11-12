package com.jdmmc.kurumamod.platform;

import com.jdmmc.kurumamod.platform.McIds;
import com.jdmmc.kurumamod.car.CarId;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import com.jdmmc.kurumamod.client.Assets;

/** core の {@link Assets} を、いまのリソースパックの組（{@code ResourceManager}）で開く。 */
final class McAssets implements Assets {

    @Override
    public Map<CarId, Asset> listResources(String directory, Predicate<CarId> filter) {
        Map<CarId, Asset> out = new LinkedHashMap<>();
        Minecraft.getInstance().getResourceManager()
                .listResources(directory, location -> filter.test(McIds.of(location)))
                .forEach((location, found) -> out.put(McIds.of(location), wrap(found)));
        return out;
    }

    private static Asset wrap(Resource found) {
        return new Asset() {
            @Override
            public InputStream open() throws IOException {
                return found.open();
            }

            @Override
            public BufferedReader openAsReader() throws IOException {
                return found.openAsReader();
            }
        };
    }

    @Override
    public Optional<Asset> getResource(CarId location) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(McIds.mc(location));
        return resource.map(found -> new Asset() {
            @Override
            public InputStream open() throws IOException {
                return found.open();
            }

            @Override
            public BufferedReader openAsReader() throws IOException {
                return found.openAsReader();
            }
        });
    }
}
