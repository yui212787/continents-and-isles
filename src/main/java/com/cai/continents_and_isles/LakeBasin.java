package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 深湖湖盆（碗形盆地）：仅在 lakeType==1（深湖）时生效。
 * <p>
 * 深湖区域由 DeepLakeSuppress 抑制山脉抬升后，offset 完全由本函数 + base offset 决定：
 * <ul>
 *   <li>湖心（dd &lt; 0.55）：修正 0，base offset ≈ -0.22 → 地面约 Y43，水深 20 格（最深）</li>
 *   <li>湖边（dd → 1.0）：修正平滑升到 +0.17，offset ≈ -0.05 → 地面约 Y57.6，水深 5.4 格（浅滩）</li>
 *   <li>dd 1.0~1.6：修正平滑过渡回 0，湖岸缓坡自然衔接周围地形</li>
 * </ul>
 * 湖盆全程低于海平面（63）——挖空区全部浸在水中，含水层自然填水，无需 DeepLake 雕刻；
 * 无任何陆地凸起高于水面 → 不再出现悬浮的岛、岸边悬空。
 * <p>
 * 天池环山（TianchiMountain 在 1.15~1.45 抬升）与之叠加，湖心低洼 + 环山的效果保持。
 */
public class LakeBasin implements DensityFunction.SimpleFunction {

    public static final MapCodec<LakeBasin> DATA_CODEC = MapCodec.unit(new LakeBasin());
    public static final KeyDispatchDataCodec<LakeBasin> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    /**
     * 岸边 offset 修正量：深湖大陆度 -0.9（深海级）→ base offset ≈ -0.22（地面约 Y43，水深 20 格）。
     * +0.17 → offset ≈ -0.05（地面约 Y57.6，水面下约 5.4 格）。
     * 湖心不加修正（保持最深），向岸边平滑升到 +0.17 形成碗形。
     */
    private static final double LIFT = 0.17;

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        double best = 0.0;
        for (int i = 0; i < ContinentIslandField.LAKE_COUNT; i++) {
            if (ContinentIslandField.lakeType[i] != 1) {
                continue;
            }
            double lcx = ContinentIslandField.lakeCenterX(i);
            double lcz = ContinentIslandField.lakeCenterZ(i);
            double lr = ContinentIslandField.lakeRadiusActual[i];
            double dd = Math.sqrt((x - lcx) * (x - lcx) + (z - lcz) * (z - lcz)) / lr;
            double v;
            if (dd < 1.0) {
                // 碗形：湖心（dd 0~0.55）修正 0（最深），向湖边（dd 1.0）平滑升到 +0.17（浅滩）
                double bowl = Mth.smoothstep((float) Mth.clamp((dd - 0.55) / 0.45, 0.0, 1.0));
                v = LIFT * bowl;
            } else if (dd < 1.60) {
                // 过渡加宽到 1.60：湖盆到湖岸的 offset 变化更平缓，岸边形成缓坡而非垂直断崖
                double t = (dd - 1.0) / 0.60;
                v = LIFT * (1.0 - Mth.smoothstep((float) Mth.clamp(t, 0.0, 1.0)));
            } else {
                continue;
            }
            best = Math.max(best, v);
        }
        return best;
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return LIFT;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
