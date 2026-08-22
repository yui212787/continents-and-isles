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
 * 平台统一 64~68 起伏（64 平层 + 噪声 ±4，无 centerW 增强）。
 * 缓坡（0.30R~0.40R）从平台高度缓降下探至 Y=58（低于海平面，形成水下缓坡入海），
 * 坡面带轻微噪声蜿蜒。内缘与中心区地形由 ArchipelagoTransition 的渐入过渡负责平滑衔接。
 */
public class Flat64 implements DensityFunction.SimpleFunction {

    public static final MapCodec<Flat64> DATA_CODEC = MapCodec.unit(new Flat64());
    public static final KeyDispatchDataCodec<Flat64> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private static final Logger LOGGER = LoggerFactory.getLogger(Flat64.class);
    private static final AtomicLong SAMPLES = new AtomicLong();

    // 缓降段（dist 坐标）：0.34R 起（与压低带主体终点对齐，主体 0.30R~0.34R 保持 64 平台），
    // 0.40R 处降至 48 层（低于海平面，压低区外缘水下缓坡）
    private static final double SLOPE_LO = 0.34;
    private static final double SLOPE_HI = 0.40;
    private static final double SLOPE_END_Y = 48.0; // 缓坡终点：水下缓坡入海（48 < 海平面 63）
    private static final double WAVE_BIG = 0.006;   // 大尺度海岸线曲折（±0.008R，幅度更小）
    private static final double WAVE_SMALL = 0.002; // 小尺度港湾细节（±0.003R，幅度更小）

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        int y = context.blockY();
        double R = ContinentIslandField.continentRadius;
        double dist = Math.sqrt((double) x * x + (double) z * z);

        // 平台统一 64~68 起伏（64 平层 + 噪声 ±4）
        double platform = 64.0 + ContinentIslandField.valueNoise(x, z, 26, 8888) * 4.0;

        double targetH = platform;
        if (dist > R * SLOPE_LO && dist < R * SLOPE_HI) {
            double wobble = (ContinentIslandField.valueNoise(x, z, 40, 7771) - 0.5) * 2.0 * (R * WAVE_BIG);
            double wobble2 = (ContinentIslandField.valueNoise(x, z, 18, 7772) - 0.5) * 2.0 * (R * WAVE_SMALL);
            double dEff = dist + wobble + wobble2;
            double t = Mth.clamp((dEff - R * SLOPE_LO) / (R * (SLOPE_HI - SLOPE_LO)), 0.0, 1.0);
            targetH = platform - (platform - SLOPE_END_Y) * Mth.smoothstep((float) t); // platform → 48
        } else if (dist >= R * SLOPE_HI) {
            targetH = SLOPE_END_Y;
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
