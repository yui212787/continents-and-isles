package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 径向大陆密度函数：替换原版 overworld 的 continents 函数。
 * <p>
 * 大陆/过渡区输出 = clamp(bias + 0.10 × vanillaContinents)：
 * 以本模组的大陆场（{@link ContinentIslandField#bias}）为主，叠加少量原版大陆度形态
 * （保留海岸线曲折），把中央区域整体抬成连片大陆。
 * 外围（dist ≥ R + transition）输出 = clamp(bias)：纯岛屿场，岛屿中心为陆地、其余为深海，
 * 不受原版 continents 干扰（否则岛屿边缘会被拖成大片陆地）。
 * <p>
 * 0.10 权重刻意很小：避免 continents 值顶到 0.9+ 让 offset/factor spline 外推、地形异常耸高。
 */
public class RadialLand implements DensityFunction.SimpleFunction {

    public static final MapCodec<RadialLand> DATA_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("base").forGetter(RadialLand::base),
            Codec.INT.fieldOf("radius").forGetter(RadialLand::radius),
            Codec.INT.fieldOf("transition").forGetter(RadialLand::transition),
            Codec.INT.fieldOf("grid").forGetter(RadialLand::grid),
            Codec.DOUBLE.fieldOf("island_chance").forGetter(RadialLand::islandChance)
        ).apply(instance, RadialLand::new)
    );

    public static final KeyDispatchDataCodec<RadialLand> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final DensityFunction base;
    private final int radius;
    private final int transition;
    private final int grid;
    private final double islandChance;

    public RadialLand(DensityFunction base, int radius, int transition, int grid, double islandChance) {
        this.base = base;
        // JSON 传入的 radius/transition/grid/islandChance 只是注册期静态占位——
        // 注册期早于配置文件加载，这些值仅供 codec 编解码；
        // compute() 每次从 ContinentIslandField 静态变量（由 loadConfig/ensureConfigLoaded 更新）读取真实值。
        this.radius = radius;
        this.transition = transition;
        this.grid = grid;
        this.islandChance = islandChance;
    }

    public DensityFunction base() {
        return this.base;
    }

    public int radius() {
        return this.radius;
    }

    public int transition() {
        return this.transition;
    }

    public int grid() {
        return this.grid;
    }

    public double islandChance() {
        return this.islandChance;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        // 注册期早于配置加载，构造函数里的缓存值不可靠；
        // compute 每次根据 ContinentIslandField 静态变量（loadConfig 已更新）获取当前配置
        int R = ContinentIslandField.continentRadius;
        int T = ContinentIslandField.continentTransition;
        int G = ContinentIslandField.continentGrid;
        double IC = ContinentIslandField.continentIslandChance;
        ContinentIslandField.Config cfgCur = new ContinentIslandField.Config(R, T, G, IC);
        double bias = ContinentIslandField.bias(
            context.blockX(),
            context.blockZ(),
            cfgCur
        );
        double x = context.blockX();
        double z = context.blockZ();
        double dist = Math.sqrt(x * x + z * z);
        if (dist >= R + T) {
            // 外围：纯岛屿场——岛屿中心为陆地、其余为深海，不受原版 continents 干扰（否则岛屿边缘会被拖成大片陆地）
            return Mth.clamp(bias, -1.0, 1.0);
        }
        // 大陆及过渡区：大陆场为主 + 少量原版大陆度形态（保留海岸线曲折）
        // 权重保持很小，避免 continents 值顶到 0.9+ 让 offset/factor spline 外推、地形异常耸高
        double value = bias + 0.10 * this.base.compute(context);
        return Mth.clamp(value, -1.0, 1.0);
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
