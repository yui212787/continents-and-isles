package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 群岛扇区侵蚀抑制：在群岛扇区（含向外蔓延的外溢带与过渡带）内抑制原版侵蚀噪声，
 * 防止侵蚀在自定义小岛/过渡带地形上产生方形/规则空洞。
 * <p>
 * 关键：原版 offset spline 在过渡带 continents 区间（约 0.36 ~ -0.55）强依赖侵蚀维度，
 * 原版侵蚀噪声的低频大块图案（方形等值线）会在此挖出深谷。因此抑制必须从 ext=0 就开始
 * 覆盖整个扇区（含岸边），且中心值压到 0.05——否则岸边（ext 0.05~0.15）侵蚀仍接近 1.0，
 * 方形深坑会残留在过渡带岸线。
 * <ul>
 *   <li>ext ≤ 0（扇区外）：返回 1.0，正常侵蚀</li>
 *   <li>ext ≥ 0.15（扇区内部）：返回 0.05，强抑制（保留极少量自然感）</li>
 *   <li>0 ~ 0.15：smoothstep 快速过渡，岸边也得到抑制</li>
 * </ul>
 */
public class ArchipelagoErosionSuppress implements DensityFunction.SimpleFunction {

    public static final MapCodec<ArchipelagoErosionSuppress> DATA_CODEC = MapCodec.unit(new ArchipelagoErosionSuppress());
    public static final KeyDispatchDataCodec<ArchipelagoErosionSuppress> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        int x = context.blockX();
        int z = context.blockZ();
        double ext = ContinentIslandField.islandSectorFalloff(x, z, ContinentIslandField.continentRadius);
        if (ext <= 0.0) {
            return 1.0; // 扇区外：正常侵蚀
        }
        if (ext >= 0.15) {
            return 0.05; // 扇区内部：强抑制，消除方形深坑
        }
        // 0 ~ 0.15：smoothstep 快速过渡（岸边也压低，避免深坑残留在过渡带岸线）
        double t = Mth.smoothstep((float) Mth.clamp(ext / 0.15, 0.0, 1.0));
        return Mth.lerp(t, 1.0, 0.05);
    }

    @Override
    public double minValue() {
        return 0.05;
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
