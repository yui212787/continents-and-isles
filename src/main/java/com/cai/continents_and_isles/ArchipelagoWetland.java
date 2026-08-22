package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 群岛-环山带过渡湿地浅滩带密度函数（地形侧）。
 * <p>
 * 职责：只输出湿地带 band 值（0.0~1.0），供 {@code offset_mountain.json} 中的 lerp
 * 结构把湿地带地形双向拉向目标高度。band 判定完全复用
 * {@link ContinentIslandField#archipelagoWetlandBand}——与群系源
 * {@link ContinentsAndIslesBiomeSource} 使用完全相同的径向环带（0.80R~0.98R）、
 * 内缘只外弯的噪声岸线与群岛扇区角度掩码，保证"地形见水"与"群系为沼泽/红树林"
 * 在任何坐标都严格对齐，不会出现沼泽在干地上的错位。
 * <p>
 * lerp 混合（在 JSON 中完成，不依赖本函数读取 base）：
 * {@code offset = base × (1 - band) + TARGET × band}
 * band=1 → offset=TARGET（Y≈62）；band=0 → offset=base（原地形不变）；
 * 过渡带 → 平滑混合，不会出现"高到 100 或低到 -54"的两极分化。
 * <p>
 * 本类保留 {@code base} 字段仅用于 JSON 编解码兼容（历史配置仍声明该引用），
 * compute 中不再读取它。
 */
public class ArchipelagoWetland implements DensityFunction.SimpleFunction {

    public static final MapCodec<ArchipelagoWetland> DATA_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("base").forGetter(ArchipelagoWetland::base)
        ).apply(instance, ArchipelagoWetland::new)
    );
    public static final KeyDispatchDataCodec<ArchipelagoWetland> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    /** 仅满足 codec 编解码；compute 不读取（历史 JSON 声明该引用，保留兼容） */
    private final DensityFunction base;

    public ArchipelagoWetland(DensityFunction base) {
        this.base = base;
    }

    public DensityFunction base() {
        return this.base;
    }

    @Override
    public double compute(DensityFunction.FunctionContext ctx) {
        ContinentIslandField.ensureConfigLoaded();
        double R = ContinentIslandField.continentRadius;
        if (R <= 0) R = 4250.0; // 兜底（配置未加载的极端环境）
        // 与群系源共用同一判定，杜绝地形/群系错位（见类注释）
        return ContinentIslandField.archipelagoWetlandBand(ctx.blockX(), ctx.blockZ(), R);
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
