package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 边缘环山掩码：超大陆边缘的环形山脉带（可选，默认关闭）。
 * <p>
 * 配置开启时，在 0.97R~1.0R 环带内返回 1.0（叠加 ±0.34 噪声起伏，
 * offset_mountain ×1.2 后对应 offset ±0.4 ≈ ±30 格的高差），
 * 内侧 1.5%R 平滑过渡、外侧 1%R 骤降（断崖入海）。
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

        // 环山带：0.97R~1.0R（宽度 0.03R，为原 0.12R 的四分之一）
        double lo = this.radius * 0.97;
        double hi = this.radius * 1.0;
        double fallIn = this.radius * 0.015; // 内侧 1.5%R 缓坡过渡
        double fallOut = this.radius * 0.01; // 外侧 1%R 骤降（断崖）

        if (dist < lo - fallIn || dist > hi + fallOut) {
            return 0.0;
        }
        double base;
        if (dist < lo) {
            base = Mth.smoothstep((float) ((dist - (lo - fallIn)) / fallIn));
        } else if (dist > hi) {
            base = 1.0 - Mth.smoothstep((float) ((dist - hi) / fallOut));
        } else {
            base = 1.0;
        }
        // 环山带整体很高，但叠加 ±0.34 噪声起伏（offset_mountain ×1.2 → offset ±0.4 ≈ ±30 格），
        // 形成高低起伏的山峰带而不是一堵平墙
        double n = (ContinentIslandField.valueNoise(x, z, 280, 3017) - 0.5) * 0.68;
        return Math.max(0.0, base * (1.0 + n));
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.34;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
