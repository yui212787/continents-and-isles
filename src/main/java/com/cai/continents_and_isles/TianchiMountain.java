package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 天池环山密度函数：山峰扇区湖为天池（lakeTianchi[1]==true）时，
 * 在湖心周围生成一圈环形山体（1.15R~1.45R 湖半径抬升），湖心保持低洼（水）。
 * <p>
 * 非天池时始终返回 0，不影响任何地形。
 */
public class TianchiMountain implements DensityFunction.SimpleFunction {

    public static final MapCodec<TianchiMountain> DATA_CODEC = MapCodec.unit(new TianchiMountain());
    public static final KeyDispatchDataCodec<TianchiMountain> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        if (!ContinentIslandField.lakeTianchi[1]) {
            return 0.0;
        }
        int x = context.blockX();
        int z = context.blockZ();
        double cx = ContinentIslandField.lakeCenterX(1);
        double cz = ContinentIslandField.lakeCenterZ(1);
        double r = ContinentIslandField.lakeRadiusActual[1];
        double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) / r;

        if (d <= 1.15 || d >= 1.45) {
            return 0.0;
        }
        // 内坡升（1.15~1.30）→ 外坡降（1.30~1.45）
        double t;
        if (d < 1.30) {
            t = (d - 1.15) / 0.15;
        } else {
            t = 1.0 - (d - 1.30) / 0.15;
        }
        double peak = Mth.clamp(t, 0.0, 1.0);
        // 山体不规则：加噪声起伏
        double n = ContinentIslandField.valueNoise(x, z, 160, 3013);
        double height = peak * (0.55 + 0.45 * n);
        return Math.max(0.0, height);
    }

    @Override
    public double minValue() {
        return 0.0;
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
