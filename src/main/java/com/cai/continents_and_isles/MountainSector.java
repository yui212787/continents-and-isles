package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 山峰扇区掩码：与 {@link ContinentsAndIslesBiomeSource} 使用同一扇区判定
 * （扇区 0，中心角 -150°，半宽 20°，径向 0.30R~0.955R 直达环山带），
 * 扇区内返回 0.30~1.0（WiFi 弧线结构：峡谷底 ≈0.30、外弧低、内弧高）、边缘平滑衰减到 0.0。
 * <p>
 * 扇区整体方位由世界种子随机旋转（与群系源共用 {@link ContinentIslandField#sectorRotation()}），
 * 供 offset / jaggedness 密度函数抬升山峰区地形高度，
 * 让「尖峭 / 冰封 / 裸岩山峰」群系出现在弧线山脊上、峡谷回落低地群系。
 */
public class MountainSector implements DensityFunction.SimpleFunction {

    public static final MapCodec<MountainSector> DATA_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            Codec.INT.fieldOf("radius").forGetter(MountainSector::radius)
        ).apply(instance, MountainSector::new)
    );

    public static final KeyDispatchDataCodec<MountainSector> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final int radius;

    public MountainSector(int radius) {
        // 用配置值覆盖 JSON 参数（JSON 中的值仅为占位）
        this.radius = CAIConfig.RADIUS.get();
    }

    public int radius() {
        return this.radius;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        double x = context.blockX();
        double z = context.blockZ();

        // 与群系源共用同一山脉结构函数（蜿蜒 mask + 峰谷结构），保证地形抬升与山脉群系完全一致
        double mask = ContinentIslandField.mountainValue(x, z, this.radius);
        if (mask <= 0.0) {
            return 0.0;
        }

        // 湖泊区域抬升衰减：所有湖半径 1.5 倍内平滑降为 0，避免山峰抬升把湖挖成深井/悬崖。
        // 天池（lakeTianchi[1]）的环山由 tianchi_mountain 独立提供，此处仅保证湖盆不抬高。
        for (int i = 0; i < ContinentIslandField.LAKE_COUNT; i++) {
            double lcx = ContinentIslandField.lakeCenterX(i);
            double lcz = ContinentIslandField.lakeCenterZ(i);
            double lr = ContinentIslandField.lakeRadiusActual[i];
            double d = Math.sqrt((x - lcx) * (x - lcx) + (z - lcz) * (z - lcz));
            double fallStart = lr * 1.10;
            double fallEnd = lr * 1.50;
            if (d < fallEnd) {
                double f = d <= fallStart ? 0.0 : Mth.smoothstep((float) ((d - fallStart) / (fallEnd - fallStart)));
                mask *= f;
                if (mask <= 0.0) {
                    return 0.0;
                }
            }
        }

        return Math.max(0.0, mask);
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
