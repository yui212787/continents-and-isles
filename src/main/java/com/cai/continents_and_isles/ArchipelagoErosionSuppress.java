package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 群岛扇区侵蚀抑制：在群岛扇区内抑制原版侵蚀噪声，防止侵蚀在自定义小岛地形上
 * 产生方形/规则空洞。在扇区外部返回 1.0（正常侵蚀），扇区中心返回 0.20（保留
 * 少量自然侵蚀），过渡带平滑过渡。
 */
public class ArchipelagoErosionSuppress implements DensityFunction.SimpleFunction {

    public static final MapCodec<ArchipelagoErosionSuppress> DATA_CODEC = MapCodec.unit(new ArchipelagoErosionSuppress());
    public static final KeyDispatchDataCodec<ArchipelagoErosionSuppress> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        ContinentIslandField.Config cfg = new ContinentIslandField.Config(
            ContinentIslandField.continentRadius, 600, 400, 0.22);
        double ext = ContinentIslandField.islandSectorFalloff(x, z, cfg);
        if (ext <= 0.03) {
            return 1.0; // 扇区外：正常侵蚀
        }
        if (ext >= 0.35) {
            return 0.20; // 扇区中心：强抑制，保留少量自然感
        }
        // 过渡带 0.03~0.35：smoothstep 过渡
        double t = Mth.smoothstep((float) Mth.clamp((ext - 0.03) / 0.32, 0.0, 1.0));
        return Mth.lerp(t, 1.0, 0.20);
    }

    @Override
    public double minValue() {
        return 0.20;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
