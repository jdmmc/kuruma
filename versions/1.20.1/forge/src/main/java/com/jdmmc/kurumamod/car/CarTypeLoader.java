package com.jdmmc.kurumamod.car;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.jdmmc.kurumamod.platform.McIds;
import com.jdmmc.kurumamod.car.CarId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * データパックから車種の諸元を読む。<b>サーバー側だけで動く。</b>
 *
 * <p>読むのは {@code data/<ns>/cars/<path>.json}。ファイルの位置がそのまま
 * {@code <ns>:<path>} という車種の id になる。</p>
 *
 * <h2>中身はセッティングのプリセットと同じ形</h2>
 *
 * <p>{@code 項目名 → 内部単位の値}。{@code CarPresets} や
 * {@code config/kurumamod-setups.json} とまったく同じなので、<b>ゲーム内で作り込んで
 * 書き出したものをそのまま持ってこられる</b>。手で数字を書くより確実で、
 * カーパックを作る人に「まず乗って詰める」という正しい順序を強制できる。</p>
 *
 * <pre>
 * {
 *   "order": 10,
 *   "values": {
 *     "wheel_base": 2.4,
 *     "mass": 950.0
 *   }
 * }
 * </pre>
 *
 * <p><b>書かれていない項目は既定値になる</b>（{@code CarSpecCodec#fromMap}）。全部書く必要はなく、
 * 既定の車から変えたいところだけ書けばよい。項目が増えても古いカーパックは読める。</p>
 *
 * <p>1 つ壊れていても他の車種は使えるべきなので、<b>失敗したファイルだけを捨てて続ける</b>。</p>
 *
 * <h2>範囲を外れた値は範囲の端へ丸める</h2>
 *
 * <p>範囲は調整画面のスライダーと同じ（{@link TunableParameter#clampInternal}）。上に外れたら上限、
 * 下に外れたら下限にして、ログに残す。<b>手で書かれる値なので桁違いが混ざる</b>——角度を度で
 * 書くと 32 ラジアンのような値がそのまま通り、スライダーでは作れない車ができていた。
 * 丸めておけば、カーパックの車に乗って調整画面を開いたときにスライダーが端に張り付いたまま
 * 動かない、ということも起きない。数として読めない値（NaN・無限大）は既定値のままにする。</p>
 */
public final class CarTypeLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    /** {@code data/<ns>/cars/} 以下を読む。 */
    public static final String DIRECTORY = CarTypeJson.DIRECTORY;

    public CarTypeLoader() {
        super(GSON, DIRECTORY);
    }

    /** 中身の読み方は core の {@link CarTypeJson}。ここは Minecraft の id を core の id に直して渡すだけ。 */
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> found, ResourceManager resources,
                         ProfilerFiller profiler) {
        Map<CarId, JsonElement> byId = new LinkedHashMap<>();
        found.forEach((id, json) -> byId.put(McIds.of(id), json));
        CarTypeJson.apply(byId);
    }
}
