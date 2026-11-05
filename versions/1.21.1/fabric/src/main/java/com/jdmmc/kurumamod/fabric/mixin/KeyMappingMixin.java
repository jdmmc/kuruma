package com.jdmmc.kurumamod.fabric.mixin;

import com.jdmmc.kurumamod.client.KurumaKeys;
import com.jdmmc.kurumamod.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同じキーに付いた割り当てを、Ctrl の有無で振り分ける（{@link KurumaKeys.Ctrl}）。
 *
 * <p>素の Minecraft は 1 つのキーに 1 つの割り当てしか届けない（{@code MAP} が 1 対 1）。NeoForge は同じキーの
 * 割り当てを全部に届け、修飾キーで選ぶ。<b>選び方は NeoForge の {@code KeyMappingLookup#getAll} と同じ</b>：
 * Ctrl を押していて、そのキーに Ctrl 付きの割り当てがあればそれだけ。無ければ Ctrl 無しの割り当て全部。
 * 離したときは、そのキーの割り当てを全部離す。</p>
 *
 * <p>ほかの MOD の挙動を変えないよう、<b>この MOD の割り当てが付いているキーでだけ</b>働く。それ以外は素のまま。</p>
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {

    @Shadow
    @Final
    private static Map<String, KeyMapping> ALL;

    @Shadow
    private int clickCount;

    @Shadow
    public abstract void setDown(boolean down);

    @Inject(method = "click", at = @At("HEAD"), cancellable = true)
    private static void kuruma$click(InputConstants.Key key, CallbackInfo ci) {
        List<KeyMapping> targets = kuruma$targets(key, false);
        if (targets == null) {
            return;
        }
        for (KeyMapping mapping : targets) {
            ((KeyMappingMixin) (Object) mapping).clickCount++;
        }
        ci.cancel();
    }

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private static void kuruma$set(InputConstants.Key key, boolean down, CallbackInfo ci) {
        List<KeyMapping> targets = kuruma$targets(key, !down);
        if (targets == null) {
            return;
        }
        for (KeyMapping mapping : targets) {
            mapping.setDown(down);
        }
        ci.cancel();
    }

    /** 届ける割り当て。この MOD の割り当てが付いていないキーなら null（素のまま）。 */
    @Unique
    private static List<KeyMapping> kuruma$targets(InputConstants.Key key, boolean releasing) {
        List<KeyMapping> plain = new ArrayList<>();
        List<KeyMapping> ctrl = new ArrayList<>();
        boolean ours = false;
        for (KeyMapping mapping : ALL.values()) {
            if (!key.equals(((KeyMappingAccessor) mapping).kuruma$getKey())) {
                continue;
            }
            ours |= mapping.getCategory().equals("key.categories.kurumamod");
            (mapping instanceof KurumaKeys.Ctrl ? ctrl : plain).add(mapping);
        }
        if (!ours || (plain.size() + ctrl.size() < 2 && ctrl.isEmpty())) {
            return null;
        }
        if (releasing) {
            List<KeyMapping> all = new ArrayList<>(ctrl);
            all.addAll(plain);
            return all;
        }
        if (KurumaKeys.Ctrl.held() && !ctrl.isEmpty()) {
            return ctrl;
        }
        return plain;
    }
}
