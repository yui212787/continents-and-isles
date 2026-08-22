package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 群岛低地抑制：在群岛扇区（含向外蔓延的外溢带）内抑制山脉弧线（mountain_sector）的附加抬升。
 * <p>
 * 群岛扇区是内海+小岛的低地水域，若山脉扇区弧线延伸到群岛区域，会出现突兀的高山。
 * 本函数在外溢场（{@link ContinentIslandField#islandSectorFalloff}）覆盖范围内返回 0
 * （把 offset_before_archipelago.json 中 mountain_sector 抬升项乘 0），让群岛扇区及周边保持低地。
 * <p>
 * 注意：本函数只乘在 mountain_sector 弧线项上，不乘在 ring_mountain 项
 * （ring_mountain 在 offset_before_archipelago.json 中是独立加项，不受本函数抑制）。
 * 不影响 base offset（由 bias 决定）——群岛小岛仍由 offset spline 正常抬升为陆地。
 */
public class IslandLowland implements DensityFunction.SimpleFunction {

    public static final MapCodec<IslandLowland> DATA_CODEC = MapCodec.unit(new IslandLowland());
    public static final KeyDispatchDataCodec<IslandLowland> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        int x = context.blockX();
        int z = context.blockZ();
        // 注意：MapCodec.unit 会在类加载时构造本实例，此时（RegisterEvent 阶段）配置文件尚未加载，
        // 不能读 CAIConfig（会抛 IllegalStateException）。这里用静态 continentRadius（loadConfig 已更新）。
        double R = ContinentIslandField.continentRadius;
        double dist = Math.sqrt((double) x * x + (double) z * z);
        // 【环山带放行】距离 ≥0.96R 时 mountain_sector 已超出其生效范围（0.30R~0.955R），
        // 强制返回 1.0 避免任何残余抑制影响环山带墙脚。
        if (dist >= R * 0.96) {
            return 1.0;
        }
        // 用外溢场判定：扇区内部 + 外溢带（ext>0.02）全部抑制山脉抬升，
        // 保证沙滩/浅海区域的高度只由 offset 修正控制，不会被山脚抬升顶高；
        // 完全离开外溢带（ext≈0）才恢复山地。
        double ext = ContinentIslandField.islandSectorFalloff(x, z, R);
        if (ext <= 0.0) {
            return 1.0;
        }
        if (ext >= 0.02) {
            return 0.0;
        }
        // ext 0~0.02 极窄过渡：外溢带边缘山地逐渐恢复
        return 1.0 - Mth.smoothstep((float) (ext / 0.02));
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
