package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 群岛扇区过渡带压低带（0~1）：覆盖群岛环带内圈（dist 0.25R~0.44R），
 * 配合 sloped_cheese.json 的 range_choice 在 band≥0.9 时直接用 Flat64（Y=64 平台）。
 * 两个候选取 max：距离驱动(0.25R~0.40R 全压) + falloff驱动(0~0.05渐入,0.05~1.00平坦)。
 * 径向限制 dist < 0.44R，0.44R 外保持原状（群岛/内海/左右两侧）。
 */
public class ArchipelagoTransition implements DensityFunction.SimpleFunction {

    public static final MapCodec<ArchipelagoTransition> DATA_CODEC = MapCodec.unit(new ArchipelagoTransition());
    public static final KeyDispatchDataCodec<ArchipelagoTransition> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchipelagoTransition.class);
    private static final AtomicLong SAMPLES = new AtomicLong();

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        ContinentIslandField.ensureConfigLoaded();
        int x = context.blockX();
        int z = context.blockZ();
        double R = ContinentIslandField.continentRadius;
        double dist = Math.sqrt((double) x * x + (double) z * z);
        if (dist >= R * 0.44) {
            return 0.0; // 压低区外缘 0.44R；0.44R 外恢复原地形（群岛/内海/左右两侧原状）
        }
        double ext = ContinentIslandField.islandSectorFalloff(x, z, R);
        double angMask = ContinentIslandField.islandSectorAngMask(x, z);

        // ===== 候选 1：内侧距离驱动全压带（0.25R~0.40R）=====
        // 沙滩带往中心区方向的区域也全压到与沙滩带平齐（band=angMask）。
        // 0.25R~0.29R 加渐入过渡：band 从 0 渐变 → sloped_cheese 的 lerp 会把
        // 中心区原地形平滑过渡到压低区平台，内缘高度接近中心区地形，不出现陡坎。
        double inwardBand = 0.0;
        if (dist >= R * 0.25 && dist <= R * 0.40) {
            if (angMask > 0.02) {
                double fade = Mth.clamp((dist - R * 0.25) / (R * 0.04), 0.0, 1.0);
                inwardBand = angMask * Mth.smoothstep((float) fade);
            }
        }

        // ===== 候选 2：falloff(ext) 常规窗口 =====
        // 覆盖压低区内部（ext 0.05~1.00），渐入 0~0.05 → 平坦 0.05~1.00。
        // 平坦段必须延伸到 ext=1.0：扇区中心 angMask≈1 处若被 0.98 排除，band 会归零，
        // 压低区正中心 flat_64 失效 → 缓坡整体看不到。
        double extBand = 0.0;
        if (ext > 0.00 && ext < 1.0) {
            if (ext < 0.05) {
                extBand = Mth.smoothstep((float) (ext / 0.05));   // 0.00~0.05：快速渐入
            } else {
                extBand = 1.0; // 核心平坦段 0.05~1.00（含扇区中心）
            }
        }

        // ===== 取较大值：两段衔接处绝不会出现 0 =====
        double band = Math.max(inwardBand, extBand);

        // ===== 角度两侧渐入过渡（复用普通带径向渐入的手法）=====
        // 压低带在角度边界（±half）原本是硬截止：角度内 band=1 → Flat64 平台 Y=64，
        // 角度外 band=0 → 大陆原地形（几百米高），形成几千米长、百米深的高度断层。
        // 这里在角度边界内侧加一条渐入带（half*0.75 ~ half，即 15°~20°）：
        // band 平滑从 1 → 0，配合 sloped_cheese 的 lerp（band 0.10~0.90 区间）
        // 把 Flat64 高度平滑回升到周围大陆高度，消除两侧断层；
        // 最外侧仍由 angMask 的 19°~20° 窄过渡保证硬截止不越界。
        double angle = Math.atan2(z, x);
        double center = ContinentIslandField.sectorCenterAngle(ContinentIslandField.ISLAND_SECTOR);
        double delta = Math.abs(Math.atan2(Math.sin(angle - center), Math.cos(angle - center)));
        double half = ContinentIslandField.islandSectorHalfRad(); // 含群岛专用扩展，压低带跟随群岛扇区向两侧扩大
        double angIn = half * 0.75;   // 渐入带内侧起点：75% 半宽（band 开始下降）
        double angOut = half * 1.00;  // 渐入带外侧终点：100% 半宽（=扇区边界，band 归零）
        double angFade = 1.0 - Mth.smoothstep((float) Mth.clamp((delta - angIn) / (angOut - angIn), 0.0, 1.0));
        band *= angFade;

        // 【临时诊断】每 20000 次采样输出一次，实测压低区 band 值（用完删除）
        if ((SAMPLES.incrementAndGet() & 0x3FFFF) == 0) {
            LOGGER.info("[AT] x={}, z={}, dist/R={}, ext={}, angMask={}, inward={}, extBand={}, angFade={}, band={}",
                x, z, dist / R, ext, angMask, inwardBand, extBand, angFade, band);
        }
        return band;
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
