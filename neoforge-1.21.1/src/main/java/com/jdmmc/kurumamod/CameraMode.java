package com.jdmmc.kurumamod;

import java.util.Locale;

/**
 * 運転中のカメラモード。<b>一人称と三人称で別々に選ぶ</b>（V で今の視点のほうを切り替える）。
 *
 * <p>一人称の既定（{@link #COCKPIT_FOLLOW}）は車体と一緒に揺れるので実車に近いが、酔う人がいる。
 * そのため水平を保つモードを並べてある。</p>
 *
 * <p><b>{@code client} パッケージには置けない。</b>{@link ClientConfig} が参照する以上、
 * spec を登録する専用サーバー側でもこのクラスが読み込まれるため（{@link GateDisplay} と同じ）。
 * 設定ファイルには名前で書くので、並べ替えても保存済みの設定は壊れない。</p>
 */
public enum CameraMode {

    /** 一人称・既定。目が車体に固定され、ロールもピッチも視界に出る。視線は車へ追従する。 */
    COCKPIT_FOLLOW(true),
    /** 一人称。目の位置は車体に付いたまま、視線は水平を保つ（首で水平を取っている状態）。追従する。 */
    COCKPIT_LEVEL(true),
    /** 一人称。車体固定のまま、視線を車へ追従させない（マウスで見た向きのまま）。 */
    COCKPIT_FREE(true),
    /** 三人称・既定。視線が車の向きと進行方向の間へゆっくり追従する。マウスで見回せる。 */
    FOLLOW(false),
    /** 三人称。左右の向きを車体の向きに固定する。上下だけマウスで動かせる。 */
    HEADING(false),
    /** 三人称。追従しない。 */
    FREE(false);

    private final boolean firstPerson;

    CameraMode(boolean firstPerson) {
        this.firstPerson = firstPerson;
    }

    public boolean isFirstPerson() {
        return firstPerson;
    }

    /** 視線を車へ自動で追従させるか（{@code CarCamera}）。 */
    public boolean followsView() {
        return this == COCKPIT_FOLLOW || this == COCKPIT_LEVEL || this == FOLLOW;
    }

    /** 一人称で、視線を車体と一緒に傾けるか（{@code CarCockpitCamera}）。 */
    public boolean tiltsWithBody() {
        return this == COCKPIT_FOLLOW || this == COCKPIT_FREE;
    }

    /** 同じ視点（一人称／三人称）のモードの中で次のもの。最後まで来たら最初へ戻る。 */
    public CameraMode next() {
        CameraMode[] modes = values();
        for (int i = 1; i <= modes.length; i++) {
            CameraMode candidate = modes[(ordinal() + i) % modes.length];
            if (candidate.firstPerson == firstPerson) {
                return candidate;
            }
        }
        return this;
    }

    /** 翻訳キー。アクションバーとテレメトリに出す。 */
    public String translationKey() {
        return "camera.kurumamod.mode." + name().toLowerCase(Locale.ROOT);
    }
}
