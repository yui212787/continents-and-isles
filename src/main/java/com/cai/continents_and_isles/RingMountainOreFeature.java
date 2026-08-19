package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

/**
 * 环山带矿石：与 {@link OreFeature} 完全一致，但只在围岛山脉（环山带）内放置。
 * <p>
 * 原版矿层（Y16 以下）被抬到极深处难以开采。本 feature 在环山带山体内按配置随机生成
 * 钻石/金/红石/铁等矿脉，让环山带挖矿有回报。
 * <p>
 * 距离判定与 RingMountain 环山带一致（dist &gt;= 0.955R 且环山开关开启），
 * 配合 biome_modifier 挂载到山峰群系后，非环山带的山地不会生成这些矿脉。
 */
public class RingMountainOreFeature extends OreFeature {

    /** 环山带进入判定：RingMountain 环山带 0.97R~1.0R + 内侧 1.5%R 过渡（0.955R 起） */
    private static final double RING_EDGE = 0.955;

    public RingMountainOreFeature(Codec<OreConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<OreConfiguration> context) {
        if (!ContinentIslandField.ringMountainEnabled) {
            return false;
        }
        double x = context.origin().getX();
        double z = context.origin().getZ();
        double dist = Math.sqrt(x * x + z * z);
        if (dist < ContinentIslandField.continentRadius * RING_EDGE) {
            return false; // 非环山带不生成
        }
        return super.place(context);
    }
}
