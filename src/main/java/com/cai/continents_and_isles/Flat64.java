package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 压低区地形表面锁定（MC 约定：正值=实心，负值=空气）：
 * 普通带（0.30R 以内）起伏 64~72；向沙滩带（0.36R 外）平滑过渡到 64~68。
 * 缓坡（0.32R~0.40R）从平台高度缓降下探至 Y=36，坡面带轻微噪声蜿蜒。
 * 内缘与中心区地形由 ArchipelagoTransition 的渐入过渡负责平滑衔接。
 */
public class Flat64 implements DensityFunction.SimpleFunction {

    public static final MapCodec<Flat64> DATA_CODEC = MapCodec.unit(new Flat64());
    public static final KeyDispatchDataCodec<Flat64> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private static final Logger LOGGER = LoggerFactory.getLogger(Flat64.class);
    private static final AtomicLong SAMPLES = new AtomicLong();

    // 缓降段（dist 坐标）：0.32R 起（沙滩带前推后小幅度后移），
    // 0.44R 处降至 36 层（压低区外缘）
    private static final double SLOPE_LO = 0.32;
    private static final double SLOPE_HI = 0.40;
    private static final double WAVE_BIG = 0.006;   // 大尺度海岸线曲折（±0.008R，幅度更小）
    private static final double WAVE_SMALL = 0.002; // 小尺度港湾细节（±0.003R，幅度更小）

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        int y = context.blockY();
        double R = ContinentIslandField.continentRadius;
        double dist = Math.sqrt((double) x * x + (double) z * z);

        // 普通带（中心侧 0.30R 以内）起伏更高 64~72，向沙滩带（0.36R 外）平滑过渡到 64~68
        double centerW = Mth.clamp(1.0 - (dist - R * 0.30) / (R * 0.06), 0.0, 1.0);
        centerW = Mth.smoothstep((float) centerW);
        double platform = 64.0 + ContinentIslandField.valueNoise(x, z, 26, 8888) * (4.0 + 4.0 * centerW);

        double targetH = platform;
        if (dist > R * SLOPE_LO && dist < R * SLOPE_HI) {
            double wobble = (ContinentIslandField.valueNoise(x, z, 40, 7771) - 0.5) * 2.0 * (R * WAVE_BIG);
            double wobble2 = (ContinentIslandField.valueNoise(x, z, 18, 7772) - 0.5) * 2.0 * (R * WAVE_SMALL);
            double dEff = dist + wobble + wobble2;
            double t = Mth.clamp((dEff - R * SLOPE_LO) / (R * (SLOPE_HI - SLOPE_LO)), 0.0, 1.0);
            targetH = platform - (platform - 36.0) * Mth.smoothstep((float) t); // platform → 36
        } else if (dist >= R * SLOPE_HI) {
            targetH = 36.0;
        }

        double v = Mth.clampedLerp(1.0, -1.0, (y - (targetH - 2.0)) / 4.0);
        if ((SAMPLES.incrementAndGet() & 0x3FFFF) == 0) {
            LOGGER.info("[F64] x={}, z={}, y={}, dist/R={}, targetH={}, value={}", x, z, y, dist / R, targetH, v);
        }
        return v;
    }

    @Override
    public double minValue() {
        return -1.0;
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
