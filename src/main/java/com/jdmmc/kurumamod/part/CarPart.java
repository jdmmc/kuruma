package com.jdmmc.kurumamod.part;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 部品 1 つ。<b>カーパックが足せる単位。</b>
 *
 * <p>車種（{@code CarType}）とまったく同じ二分割になっている——諸元はサーバーが読む
 * {@code data/}、見た目はクライアントだけが読む {@code assets/}。{@code assets/} は
 * サーバーが読まないので、そこに挙動を決める値を置くと<b>無人の車をサーバーが解くときの
 * 挙動がクライアントのリソースパック次第</b>になってしまう。</p>
 *
 * <table>
 *   <tr><th></th><th>置き場所</th><th>読む側</th></tr>
 *   <tr><td>部品の素性（どの場所に付くか）</td><td>{@code data/<ns>/car_parts/<path>.json}</td>
 *       <td>サーバー。{@link CarPartLoader} が読み、クライアントへ配る</td></tr>
 *   <tr><td>見た目（モデル・テクスチャ・取り付け位置）</td><td>{@code assets/<ns>/car_parts/<path>.json}</td>
 *       <td>クライアントだけ（{@code CarPartModel}）</td></tr>
 * </table>
 *
 * <p><b>いまは諸元を持たない。</b>付けても挙動は変わらず、見た目だけが変わる。
 * 物理に効かせるときは、ここへ諸元を足すのではなく<b>「車種の諸元 → 部品 → 調整画面」の順で
 * 実効諸元を組み立てる関数を 1 か所に作る</b>こと。{@code CarSpec} へ焼き込むと、
 * 外しても戻らない・調整画面の値と食い違う、という二重管理になる。</p>
 *
 * @param id    部品の識別子。名前空間がカーパック、パスが部品名
 * @param slot  付ける場所
 * @param order 一覧に並べる順。小さいものが先。同じなら id 順
 */
public record CarPart(ResourceLocation id, PartSlot slot, int order) {

    /** 並び順を書かなかった部品の位置。 */
    public static final int DEFAULT_ORDER = 100;

    /** 表示名の翻訳キー。{@code mypack:te37} → {@code part.mypack.te37}。 */
    public String translationKey() {
        return "part." + id.getNamespace() + "." + id.getPath();
    }

    /**
     * 画面に出す名前。<b>翻訳が無ければパスをそのまま出す。</b>
     *
     * <p>{@code CarType} と同じ理由——生のキーが並ぶよりは「te37」と出た方がましなため。</p>
     */
    public Component displayName() {
        String key = translationKey();
        Component translated = Component.translatable(key);
        return translated.getString().equals(key) ? Component.literal(id.getPath()) : translated;
    }
}
