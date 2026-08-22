package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 深湖地形抑制：在深湖（lakeType==1）区域内抑制山脉/天池的地形抬升。
 * <p>
 * 深湖位于山脉扇区，offset_mountain 中的 mountain_sector*0.45 与 tianchi_mountain*0.30
 * 会把湖盆地面顶到海平面以上——配合旧版 DeepLake 只挖到水面（Y63）就留下"悬浮的岛"
 * 与"岸边悬空"的断崖。
 * <p>
 * 本函数在深湖内（dd &lt; 1.0）返回 0，把这两项抬升乘 0，让湖盆完全由 LakeBasin 的
 * 碗形盆地决定（湖心低、岸边浅，全部在水面以下）→ 含水层自然填水，无陆地凸起、无悬空。
 * dd 1.0~1.15 平滑恢复到 1（天池环山从 1.15R 起，不受影响）。
 */
public class DeepLakeSuppress implements DensityFunction.SimpleFunction {

    public static final MapCodec<DeepLakeSuppress> DATA_CODEC = MapCodec.unit(new DeepLakeSuppress());
    public static final KeyDispatchDataCodec<DeepLakeSuppress> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        int x = context.blockX();
        int z = context.blockZ();
        double best = 1.0;
        for (int i = 0; i < ContinentIslandField.LAKE_COUNT; i++) {
            if (ContinentIslandField.lakeType[i] != 1) {
                continue;
            }
            double lcx = ContinentIslandField.lakeCenterX(i);
            double lcz = ContinentIslandField.lakeCenterZ(i);
            double lr = ContinentIslandField.lakeRadiusActual[i];
            double dx = (x - lcx) / lr;
            double dz = (z - lcz) / lr;
            double dd = Math.sqrt(dx * dx + dz * dz);
            double v;
            if (dd < 1.0) {
                v = 0.0;
            } else if (dd < 1.15) {
                v = Mth.smoothstep((float) ((dd - 1.0) / 0.15));
            } else {
                continue;
            }
            best = Math.min(best, v);
        }
        return best;
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
