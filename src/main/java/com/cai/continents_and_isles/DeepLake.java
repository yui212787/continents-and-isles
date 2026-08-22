package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 深湖 3D 密度函数：仅在 lakeType==1（深湖）时生效。
 * <p>
 * 加入 final_density，通过极端值覆盖原始密度：
 * <ul>
 *   <li>水面以下、湖底以上：返回大负值 → 强制为水（雕刻平滑碗形湖底）</li>
 *   <li>湖底以下 5 格：返回大正值 → 强制为实心（5 层湖底，材质由噪声设置决定：石头层=石头，深板岩层=深板岩）</li>
 *   <li>水面以上或非深湖：返回 0 → 不影响原始密度</li>
 * </ul>
 * <p>
 * 湖底是平滑碗形（湖岸浅 → 湖心 20 格深，最深处 Y43；实际岸边水深由 LakeBasin 抬高的
 * 湖盆地面决定 ≈5.4 格），挖空严格限制在 Y63 水面以下，
 * 配合 LakeBasin 把湖盆地面抬到海平面附近——整个挖空区域都在水下，无水空洞、无垂直断崖。
 * 性能优化：先做距离平方检查（dd2 >= 1.0），大部分方块直接返回 0。
 */
public class DeepLake implements DensityFunction.SimpleFunction {

    public static final MapCodec<DeepLake> DATA_CODEC = MapCodec.unit(new DeepLake());
    public static final KeyDispatchDataCodec<DeepLake> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private static final double CARVE = -1000000.0;
    private static final double FILL = 1000000.0;
    private static final int FLOOR_THICKNESS = 5;
    private static final int SEA_LEVEL = 63;

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();

        // 遍历所有深湖（理论上每世界仅一个 lakeType==1）
        for (int i = 0; i < ContinentIslandField.LAKE_COUNT; i++) {
            if (ContinentIslandField.lakeType[i] != 1) {
                continue;
            }
            double bottomY = ContinentIslandField.deepLakeBottomY(i, x, z);
            if (Double.isNaN(bottomY)) {
                continue; // 不在该湖区内
            }
            // 只雕刻水面以下的湖体（水面以上由 LakeBasin 抬升的湖岸陆地保留，避免无水空洞）
            if (y <= SEA_LEVEL && y > bottomY) {
                return CARVE;
            }
            if (y > bottomY - FLOOR_THICKNESS && y <= bottomY) {
                return FILL;
            }
            return 0.0;
        }
        return 0.0;
    }

    @Override
    public double minValue() {
        return -1000000.0;
    }

    @Override
    public double maxValue() {
        return 1000000.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
