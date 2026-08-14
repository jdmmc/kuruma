package com.jdmmc.kurumamod.race;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * 1 つのコース。
 *
 * <p>線の並びの<b>先頭がスタート／ゴール</b>で、残りはチェックポイント。1 周と認めるのは
 * 「チェックポイントを順に全部通ってから、またスタートラインを通ったとき」。</p>
 *
 * <p>チェックポイントが無いコースでも走れるが、その場合はショートカットを検出できない。
 * 8 の字のように<b>通過方向だけで一意に決まる</b>形なら不要で、周回路なら足した方がよい。</p>
 */
public final class Course {

    /** 短すぎるラップを弾く。線の上で前後して周回数を稼ぐのを防ぐ最後の砦。 */
    public static final long MIN_LAP_MILLIS = 3000L;

    private final String name;
    private final List<CourseLine> lines = new ArrayList<>();
    private int laps = 3;

    public Course(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** レースの規定周回数。タイムアタックでは使わない。 */
    public int laps() {
        return laps;
    }

    public void setLaps(int laps) {
        this.laps = Math.max(1, laps);
    }

    /** スタート／ゴールライン。まだ引かれていなければ null。 */
    public CourseLine start() {
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** チェックポイントの数（スタートラインを除く）。 */
    public int checkpointCount() {
        return Math.max(0, lines.size() - 1);
    }

    public CourseLine checkpoint(int index) {
        return lines.get(index + 1);
    }

    /** スタート／ゴールラインを引き直す。 */
    public void setStart(CourseLine line) {
        if (lines.isEmpty()) {
            lines.add(line);
        } else {
            lines.set(0, line);
        }
    }

    /** チェックポイントを末尾に足す。通る順に足していく。 */
    public void addCheckpoint(CourseLine line) {
        lines.add(line);
    }

    public void clearCheckpoints() {
        while (lines.size() > 1) {
            lines.remove(lines.size() - 1);
        }
    }

    public boolean isReady() {
        return !lines.isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.putInt("Laps", laps);
        ListTag list = new ListTag();
        for (CourseLine line : lines) {
            list.add(line.save());
        }
        tag.put("Lines", list);
        return tag;
    }

    public static Course load(CompoundTag tag) {
        Course course = new Course(tag.getString("Name"));
        course.laps = Math.max(1, tag.getInt("Laps"));
        ListTag list = tag.getList("Lines", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            course.lines.add(CourseLine.load(list.getCompound(i)));
        }
        return course;
    }
}
