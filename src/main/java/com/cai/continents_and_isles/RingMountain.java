package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 边缘环山掩码：超大陆边缘的环形山脉带（可选，默认关闭）。
 * <p>
 * 配置开启时，在 0.97R~1.0R 环带内返回 1.0（削尖刃脊峰顶，平均 ≈1.38、尖峰最高 ≈1.64），
 * 内外两侧各 ~0.005R（≈21 格）陡崖式升降：内壁如高墙、外壁如海崖。
 * 配置关闭时始终返回 0，不影响任何地形。
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
        this.radius = CAIConfig.RADIUS.get();
    }

    public int radius() {
        return this.radius;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        if (!ContinentIslandField.ringMountainEnabled) {
            return 0.0;
        }
        double x = context.blockX();
        double z = context.blockZ();
        double dist = Math.sqrt(x * x + z * z);

        // 环山带：0.97R~1.0R 顶部高原 + 内外两侧陡崖（监狱高墙式）
        // 内壁/外壁各 ~0.005R（≈21 格）内完成 0→满值，形成近垂直的墙
        double lo = this.radius * 0.97;
        double hi = this.radius * 1.0;
        double cliffIn = this.radius * 0.005;
        double cliffOut = this.radius * 0.005;

        if (dist < lo - cliffIn || dist > hi + cliffOut) {
            return 0.0;
        }
        double base;
        if (dist < lo) {
            // 内壁：t² 上升 → 墙脚缓、近顶陡，顶部刃缘干脆（像高墙）
            double t = (dist - (lo - cliffIn)) / cliffIn;
            base = t * t;
        } else if (dist > hi) {
            // 外壁：(1-t)² 下降 → 顶部急坠、底部入海渐缓（像海崖）
            double t = (dist - hi) / cliffOut;
            base = (1.0 - t) * (1.0 - t);
        } else {
            base = 1.0;
        }

        // 削尖：刃脊噪声（V 形）幂次收窄 → 尖峰/刀刃脊，取代原来的圆顶起伏
        // 换算 Y ≈ 128 + 128×offset（×0.72）：平均 ≈280、尖峰最高 ≈305，不超 319 限高
        double spine1 = 1.0 - Math.abs(2.0 * ContinentIslandField.valueNoise(x, z, 60, 3019) - 1.0);
        double spine2 = 1.0 - Math.abs(2.0 * ContinentIslandField.valueNoise(x, z, 220, 3020) - 1.0);
        double sharp = 0.22 * Math.pow(spine1, 2.2) + 0.12 * Math.pow(spine2, 2.2);
        double slow = (ContinentIslandField.valueNoise(x, z, 600, 3021) - 0.5) * 0.10;
        double peak = 1.28 + slow + sharp;
        return Math.max(0.0, base * peak);
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.70;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
