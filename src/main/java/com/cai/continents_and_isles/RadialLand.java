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
 * 输出 = clamp(vanillaContinents + {@link ContinentIslandField#bias})：
 * 保留原版大陆度的自然起伏（从而保留真实多样的地形：丘陵、山脉、平原、海岸），
 * 同时把中央区域整体抬成连片大陆、把外围压成深海并散布单个岛屿。
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
    /** 扇区配置：构造函数读取并缓存（compute 高频调用，避免每次 new） */
    private final ContinentIslandField.Config cfg;

    public RadialLand(DensityFunction base, int radius, int transition, int grid, double islandChance) {
        this.base = base;
        // 用配置值覆盖 JSON 参数（JSON 中的值仅为占位）
        this.radius = CAIConfig.RADIUS.get();
        this.transition = CAIConfig.TRANSITION.get();
        this.grid = CAIConfig.GRID.get();
        this.islandChance = CAIConfig.ISLAND_CHANCE.get();
        this.cfg = new ContinentIslandField.Config(
            this.radius, this.transition, this.grid, this.islandChance);
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
        double bias = ContinentIslandField.bias(
            context.blockX(),
            context.blockZ(),
            this.cfg
        );
        double x = context.blockX();
        double z = context.blockZ();
        double dist = Math.sqrt(x * x + z * z);
        if (dist >= this.radius + this.transition) {
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
