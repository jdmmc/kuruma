package com.jdmmc.kurumamod.car;

/**
 * 車種・部品・モデル・テクスチャ・音などの名前（{@code kurumamod:s15}・{@code mypack:te37}・{@code kurumamod:textures/entity/s15.png}）。バニラの {@code ResourceLocation}
 * （26.3 は {@code Identifier}）の代わりで、<b>core は Minecraft を知らないのでこれで持つ。</b>
 *
 * <p>使える文字と読み方はバニラと同じ（名前空間は {@code [a-z0-9_.-]}、パスはそれに {@code /} を足したもの。
 * {@code :} が無ければ名前空間は {@code minecraft}）。文字列にした形（{@link #toString}）もバニラと同じなので、
 * 保存データ・パケット・JSON に書いてある id はそのまま読める。Minecraft の機能（リソースを開く・音を引く）へ
 * 渡すときは、版の {@code McIds} でバニラの型に直す。</p>
 */
public record CarId(String namespace, String path) implements Comparable<CarId> {

    public static final String DEFAULT_NAMESPACE = "minecraft";

    public CarId {
        if (!validNamespace(namespace) || !validPath(path)) {
            throw new IllegalArgumentException("id に使えない文字があります: " + namespace + ":" + path);
        }
    }

    public static CarId of(String namespace, String path) {
        return new CarId(namespace, path);
    }

    /** {@code ns:path} を読む。読めなければ null（バニラの {@code tryParse} と同じ）。 */
    public static CarId parse(String text) {
        if (text == null) {
            return null;
        }
        int colon = text.indexOf(':');
        String namespace = colon >= 0 ? text.substring(0, colon) : DEFAULT_NAMESPACE;
        String path = colon >= 0 ? text.substring(colon + 1) : text;
        if (namespace.isEmpty()) {
            namespace = DEFAULT_NAMESPACE;
        }
        return validNamespace(namespace) && validPath(path) ? new CarId(namespace, path) : null;
    }

    /** 読めなければ例外（バニラの {@code parse} と同じ。カーパックの JSON の書き間違いを知らせるため）。 */
    public static CarId parseStrict(String text) {
        CarId id = parse(text);
        if (id == null) {
            throw new IllegalArgumentException("id として読めません: " + text);
        }
        return id;
    }

    private static boolean validNamespace(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPath(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '_' || c == '-' || c == '.' || c == '/' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    /** バニラの {@code getNamespace} と同じ名前（呼ぶ側を変えずに済むように）。 */
    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    /** パスを差し替えた id（{@code withPath}）。 */
    public CarId withPath(String newPath) {
        return new CarId(namespace, newPath);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    @Override
    public int compareTo(CarId other) {
        int c = path.compareTo(other.path);
        return c != 0 ? c : namespace.compareTo(other.namespace);
    }
}
