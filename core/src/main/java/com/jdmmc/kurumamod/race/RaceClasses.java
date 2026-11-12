package com.jdmmc.kurumamod.race;

import java.nio.file.Path;

/**
 * クラス（名前を付けて保存したレギュレーション）の入口。中身は {@link RaceClassBook}。
 *
 * <p>ここは「どのワールドのファイルか」を決めるだけ（ワールドのフォルダの {@code kurumamod/race_classes.json}）。
 * シングルプレイでワールドを替えたら読み直す。ワールドのフォルダは版が渡す（{@code McRaceServer#classes}）。</p>
 */
public final class RaceClasses {

    private static Path loadedFor;
    private static RaceClassBook book;

    private RaceClasses() {
    }

    /** そのワールドのクラスの帳面。{@code worldDirectory} はワールドのフォルダ（{@code level.dat} のある場所）。 */
    public static RaceClassBook book(Path worldDirectory) {
        if (!worldDirectory.equals(loadedFor) || book == null) {
            loadedFor = worldDirectory;
            book = new RaceClassBook(worldDirectory.resolve("kurumamod").resolve("race_classes.json"));
        }
        return book;
    }

    /** サーバーが止まったら捨てる（次に入ったワールドで読み直す）。 */
    public static void clear() {
        loadedFor = null;
        book = null;
    }
}
