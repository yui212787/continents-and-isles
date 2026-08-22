package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 边缘环山掩码：超大陆边缘的环形山脉带（配置项控制，默认开启）。
 * <p>
 * 开启时在 0.97R~1.0R 环带内抬升（削尖刃脊峰顶，平均 ≈1.80、尖峰最高 ≈2.20，
 * 叠加 JSON ×0.72 系数 → 实际地形 Y≈294，波动 265~319）。
 * 内外两侧各 ~0.005R（≈21 格）陡崖升降：内壁挺立高墙接湿地、外壁海崖入海。
 * 关闭时始终返回 0，不影响任何地形。
 */
public class RingMountain implements DensityFunction.SimpleFunction {

    public static final MapCodec<RingMountain> DATA_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            Codec.INT.fieldOf("radius").forGetter(RingMountain::radius)
        ).apply(instance, RingMountain::new)
    );

    public static final KeyDispatchDataCodec<RingMountain> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final int radius;

    public RingMountain(int radius) {
        // JSON 中的 radius 是注册期静态占位，注册期早于配置加载，
        // 不能读 CAIConfig。compute() 用 ContinentIslandField.continentRadius 静态变量。
        this.radius = radius; // 仅满足 codec getter，compute 中不用
    }

    public int radius() {
        return this.radius;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        if (!ContinentIslandField.ringMountainEnabled) {
            return 0.0;
        }
        double R = ContinentIslandField.continentRadius;
        double x = context.blockX();
        double z = context.blockZ();
        double dist = Math.sqrt(x * x + z * z);

        // 环山带：0.97R~1.0R 顶部高原 + 内外两侧陡崖（挺立高墙式）
        // 内壁/外壁各 ~0.005R（≈21 格）内完成 0→满值：内墙直上直下接湿地、外壁海崖入海。
        // 加噪声扰动打破完美环形，山脊线蜿蜒。
        double warpIn = (ContinentIslandField.valueNoise(x, z, 200, 3022) - 0.5) * 2.0 * R * 0.015;
        double warpOut = (ContinentIslandField.valueNoise(x, z, 160, 3023) - 0.5) * 2.0 * R * 0.012;
        double lo = R * 0.97 + warpIn;
        double hi = R * 1.0 + warpOut;
        double cliffIn = R * 0.005;
        double cliffOut = R * 0.005;

        if (dist < lo - cliffIn || dist > hi + cliffOut) {
            return 0.0;
        }
        double base;
        if (dist < lo) {
            // 内壁：t² → 墙脚缓、近顶陡，顶部刃缘干脆（挺立高墙，垂直）
            double t = (dist - (lo - cliffIn)) / cliffIn;
            base = t * t;
        } else if (dist > hi) {
            // 外壁：(1-t)² 下降 → 顶部急坠、底部入海渐缓（海崖）
            double t = (dist - hi) / cliffOut;
            base = (1.0 - t) * (1.0 - t);
        } else {
            base = 1.0;
        }

        // 削尖：刃脊噪声（V 形）幂次收窄 → 尖峰/刀刃脊
        // 实际地形高度（叠加 JSON ×0.72 系数）：Y = 128 + 128 × peak × base × 0.72
        //   peak≈1.80 (基准) → Y≈294；最高 peak≈2.20 → 被 Math.min(2.30) 封顶，Y≈319（接近世界上限）
        double spine1 = 1.0 - Math.abs(2.0 * ContinentIslandField.valueNoise(x, z, 60, 3019) - 1.0);
        double spine2 = 1.0 - Math.abs(2.0 * ContinentIslandField.valueNoise(x, z, 220, 3020) - 1.0);
        double sharp = 0.22 * Math.pow(spine1, 2.2) + 0.12 * Math.pow(spine2, 2.2);
        double slow = (ContinentIslandField.valueNoise(x, z, 600, 3021) - 0.5) * 0.15;
        double peak = 1.80 + slow + sharp;
        return Math.max(0.0, Math.min(2.30, base * peak));
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 2.30;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
