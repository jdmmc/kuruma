package com.jdmmc.kurumamod.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 設定ファイルに要る分だけの TOML の読み書き（{@link ConfigSpec} が使う）。
 *
 * <p>読めるのは、平らな {@code key = value} の行、{@code #} から行末までの注釈、空行。値は文字列のまま返し、
 * 型に合わせて読むのは {@link ConfigSpec} の側。表（{@code [section]}）の行は飛ばす。
 * NeoForge（NightConfig）が書いていたファイルはこの範囲に収まる。</p>
 */
final class TomlLite {

    private TomlLite() {
    }

    /** キーと、値の部分の文字列（注釈を除き、前後の空白を落としたもの）。 */
    static Map<String, String> read(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String rawLine : text.split("\r?\n")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            out.put(key, line.substring(eq + 1).trim());
        }
        return out;
    }

    /** 文字列の外にある {@code #} から後ろを落とす。 */
    private static String stripComment(String line) {
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inDouble) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** {@code "..."} か {@code '...'}。どちらでもなければ null。 */
    static String parseString(String text) {
        List<String> one = new ArrayList<>();
        int end = readString(text, 0, one);
        if (end < 0 || !text.substring(end).isBlank()) {
            return null;
        }
        return one.get(0);
    }

    /** {@code ["a", "b"]}。読めなければ null。 */
    static List<String> parseStringList(String text) {
        String body = text.trim();
        if (!body.startsWith("[") || !body.endsWith("]")) {
            return null;
        }
        List<String> out = new ArrayList<>();
        int i = 1;
        int last = body.length() - 1;
        while (true) {
            while (i < last && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) {
                i++;
            }
            if (i >= last) {
                return out;
            }
            i = readString(body, i, out);
            if (i < 0) {
                return null;
            }
        }
    }

    /** {@code text} の {@code start} から文字列を 1 つ読んで {@code out} に足し、その次の位置を返す。読めなければ -1。 */
    private static int readString(String text, int start, List<String> out) {
        int i = start;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length()) {
            return -1;
        }
        char quote = text.charAt(i);
        if (quote == '\'') {
            int close = text.indexOf('\'', i + 1);
            if (close < 0) {
                return -1;
            }
            out.add(text.substring(i + 1, close));
            return close + 1;
        }
        if (quote != '"') {
            return -1;
        }
        StringBuilder value = new StringBuilder();
        for (int j = i + 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '"') {
                out.add(value.toString());
                return j + 1;
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (++j >= text.length()) {
                return -1;
            }
            char e = text.charAt(j);
            switch (e) {
                case 'n' -> value.append('\n');
                case 't' -> value.append('\t');
                case 'r' -> value.append('\r');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case 'u', 'U' -> {
                    int len = e == 'u' ? 4 : 8;
                    if (j + len >= text.length()) {
                        return -1;
                    }
                    value.appendCodePoint(Integer.parseInt(text.substring(j + 1, j + 1 + len), 16));
                    j += len;
                }
                default -> {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 文字列を {@code "..."} で書く。 */
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
