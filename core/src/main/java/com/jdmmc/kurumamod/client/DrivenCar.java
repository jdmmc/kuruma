package com.jdmmc.kurumamod.client;

import com.jdmmc.kurumamod.car.CarId;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.physics.CarSpec;

/**
 * 乗っている車を、車まわりの画面（換装・座席・セッティング）から見たもの。版の {@code CarEntity} を包む。
 * 名前は {@code CarEntity} のメソッドに揃えてある（画面のコードを変えずに済むように）。
 */
public interface DrivenCar {

    /** エンティティの id（付け替えの要求に載せる）。 */
    int getId();

    CarId getCarId();

    CarSpec getSpec();

    /** 装着状態（初期装備を埋める前の、プレイヤーが選んだもの）。 */
    PartFitment getFitment();

    /** 初期装備で埋めた、実際に付いている装着状態。 */
    PartFitment getEffectiveFitment();

    /** 手元だけで装着状態を変える（換装画面の試し付け。サーバーへは別に要求する）。 */
    void setFitment(PartFitment fitment);

    boolean hasTurbo();

    boolean hasBlowoff();

    /** 同じ車か（包み直しても同じエンティティなら true）。 */
    boolean same(DrivenCar other);
}
