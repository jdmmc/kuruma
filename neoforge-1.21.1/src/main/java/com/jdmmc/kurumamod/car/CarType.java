package com.jdmmc.kurumamod.car;

import com.jdmmc.kurumamod.physics.CarSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 車種 1 つ。<b>カーパックが足せる単位。</b>
 *
 * <p>1 台の車は「諸元」と「見た目」の 2 つでできているが、<b>置き場所を分けなければならない</b>。
 * {@code assets/} はクライアント専用でサーバーが読まないので、そこに諸元を置くと
 * <b>無人の車をサーバーが解くときの挙動がクライアントのリソースパック次第</b>になってしまう。</p>
 *
 * <table>
 *   <tr><th></th><th>置き場所</th><th>読む側</th></tr>
 *   <tr><td>諸元（{@link CarSpec}）</td><td>{@code data/<ns>/cars/<path>.json}</td>
 *       <td>サーバー。{@link CarTypeLoader} が読み、クライアントへ配る</td></tr>
 *   <tr><td>見た目（モデル・テクスチャ・座席）</td><td>{@code assets/<ns>/vehicles/<path>.json}</td>
 *       <td>クライアントだけ（{@code CarModel}）</td></tr>
 * </table>
 *
 * <p>この 2 つを結ぶのが {@link #id()}。{@code mypack:ae86} なら
 * {@code data/mypack/cars/ae86.json} と {@code assets/mypack/vehicles/ae86.json} を指す。
 * <b>Forge の MOD jar は自動でデータパック兼リソースパックとして読まれる</b>ので、
 * カーパックは<b>コードを持たない jar 1 つ</b>で配れる。</p>
 *
 * @param id    車種の識別子。名前空間がカーパック、パスが車種名
 * @param spec  諸元。書かれていない項目は {@link CarSpec#DEFAULT} に落ちる
 * @param order 一覧に並べる順。小さいものが先。同じなら id 順
 */
public record CarType(ResourceLocation id, CarSpec spec, int order) {

    /** 並び順を書かなかった車種の位置。 */
    public static final int DEFAULT_ORDER = 100;

    /**
     * 表示名の翻訳キー。{@code mypack:ae86} → {@code car.mypack.ae86}。
     *
     * <p>アイテム名と同じ形にしてあるので、カーパック側は lang ファイルへ 1 行足すだけでよい。</p>
     */
    public String translationKey() {
        return "car." + id.getNamespace() + "." + id.getPath();
    }

    /**
     * 画面に出す名前。<b>翻訳が無ければパスをそのまま出す。</b>
     *
     * <p>カーパックが lang を用意していなくても「car.mypack.ae86」という生のキーが
     * 並ぶよりは「ae86」と出た方がましなため。</p>
     */
    public Component displayName() {
        String key = translationKey();
        Component translated = Component.translatable(key);
        return translated.getString().equals(key) ? Component.literal(id.getPath()) : translated;
    }
}
