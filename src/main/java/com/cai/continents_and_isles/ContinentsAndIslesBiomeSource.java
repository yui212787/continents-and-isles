package com.cai.continents_and_isles;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 「大陆与群岛」世界类型的群系源。
 * <p>
 * 与密度函数 {@link RadialLand} 使用同一套 {@link ContinentIslandField} 判定：
 * <ul>
 *   <li>大陆核心：不使用原版多噪声（60+ 群系碎片化），而是用大尺度气候噪声（温度/湿度/地形起伏）
 *       从少量大陆群系池中选取，每种群系占据大片面积，形成"地形全面、群系种类不多"的超大陆</li>
 *   <li>海岸带：委托原版多噪声源（continentalness 在此从陆地滑向深海，自然给出沙滩/浅海/海洋）</li>
 *   <li>外围岛屿：每个岛屿固定为一个群系（按岛屿网格单元哈希从岛群系池中选取）</li>
 *   <li>外围深海：委托原版多噪声源给出海洋群系</li>
 * </ul>
 * <p>
 * {@link #getNoiseBiome} 的判定优先级（自上而下，先命中先返回）：
 * <ol>
 *   <li>林地府邸固定点（周围强制黑森林）</li>
 *   <li>三个必生成大湖（湖面群系按湖型固定）</li>
 *   <li>群岛-环山带过渡湿地浅滩带（沼泽/红树林，与地形 ArchipelagoWetland 严格对齐）</li>
 *   <li>超大陆内部：群岛过渡带委托原版 → 扇区群系（山脉分级/群岛/配置化主附属）→ 普通大陆群系</li>
 *   <li>超大陆之外：外岛固定单群系；深海委托原版</li>
 * </ol>
 */
public class ContinentsAndIslesBiomeSource extends BiomeSource {

    /** 诊断日志 */
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ContinentsAndIslesBiomeSource.class);

    /** 湿地带采样日志计数（限制打印次数，避免刷屏） */
    private static int wetlandLogCount = 0;

    /**
     * 单个扇区的群系配置（从配置字符串解析完成后的数据）。
     * 主群系隐含权重 = 1.0 - sum(extrasWeight)（当 extrasWeight 加和 <=1 时）；
     * 若 extrasWeight 加和 > 1，则整体归一化保证主群系占比 > 0。
     */
    private record SectorBiomeData(
        Holder<Biome> main,
        List<Holder<Biome>> extras,
        double[] extrasCumulative  // 长度 = extras.size()，值为"绝对概率"（0~1，递增，最后一个 = sumExtrasP）
    ) {}

    /** 6 个固定扇区（0..5）的群系配置 */
    private SectorBiomeData[] sectorBiomeData;

    /** 外岛群系黑名单（资源定位符），超大陆外围岛屿上禁止出现的群系 */
    private Set<ResourceLocation> outerIslandBlacklist = Set.of();

    /** Biomes O' Plenty 的 outback 群系（懒加载：模组未加载时为 null，沙漠扇区作为附属群系） */
    private Holder<Biome> bopOutbackCache;
    private boolean bopOutbackChecked;

    /** 石头滩群系（左右两侧悬崖海岸用，懒加载） */
    private Holder<Biome> stonyShoreCache;
    private boolean stonyShoreChecked;

    public static final MapCodec<ContinentsAndIslesBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
            BiomeSource.CODEC.fieldOf("delegate").forGetter(ContinentsAndIslesBiomeSource::delegate),
            Codec.INT.fieldOf("radius").forGetter(ContinentsAndIslesBiomeSource::radius),
            Codec.INT.fieldOf("transition").forGetter(ContinentsAndIslesBiomeSource::transition),
            Codec.INT.fieldOf("grid").forGetter(ContinentsAndIslesBiomeSource::grid),
            Codec.DOUBLE.fieldOf("island_chance").forGetter(ContinentsAndIslesBiomeSource::islandChance),
            Biome.CODEC.listOf().fieldOf("island_biomes").forGetter(ContinentsAndIslesBiomeSource::islandPool),
            Biome.CODEC.listOf().fieldOf("mainland_biomes").forGetter(ContinentsAndIslesBiomeSource::mainlandPool)
        ).apply(instance, ContinentsAndIslesBiomeSource::new)
    );

    private BiomeSource delegate;
    private int radius;
    private int transition;
    private int grid;
    private double islandChance;
    /** 扇区配置：构造函数读取并缓存（getNoiseBiome 高频调用，避免每次 new） */
    private ContinentIslandField.Config cfg;
    private List<Holder<Biome>> islandPool;
    private List<Holder<Biome>> mainlandPool;

    public ContinentsAndIslesBiomeSource(
        BiomeSource delegate,
        int radius,
        int transition,
        int grid,
        double islandChance,
        List<Holder<Biome>> islandPool,
        List<Holder<Biome>> mainlandPool
    ) {
        this.delegate = delegate;
        // JSON 传入的 radius/transition/grid/islandChance 只是注册期静态占位：
        // 注册期早于配置文件加载，真实数值在 ensureConfig() 首次调用时
        // 从 CAIConfig / ContinentIslandField 静态变量刷新（世界生成开始后）。
        this.radius = radius;
        this.transition = transition;
        this.grid = grid;
        this.islandChance = islandChance;
        // cfg / sectorBiomeData / outerIslandBlacklist 全部延迟到第一次 getNoiseBiome() 初始化：
        // 构造函数阶段世界生成注册表尚未完整绑定，此时遍历 delegate.possibleBiomes()
        // 或读取配置可能崩溃；首次群系分配时世界已就绪，读取安全。
        this.cfg = null;
        this.islandPool = islandPool;
        this.mainlandPool = mainlandPool;
    }

    /** 判定某群系是否在外岛黑名单中（若没有 key 或未命中返回 false） */
    private boolean isOuterIslandBlacklisted(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> this.outerIslandBlacklist.contains(key.location()))
            .orElse(false);
    }

    /** 外岛黑名单兜底：返回一个安全的岛屿群系（优先用 islandPool 第一个非黑条目 → 平原 → 森林） */
    private Holder<Biome> outerIslandFallback() {
        for (Holder<Biome> h : this.islandPool) {
            if (!isOuterIslandBlacklisted(h)) return h;
        }
        // mainlandPool 索引：1 = plains, 16 = forest
        if (this.mainlandPool.size() > 16) {
            Holder<Biome> forest = this.mainlandPool.get(16);
            if (!isOuterIslandBlacklisted(forest)) return forest;
        }
        if (this.mainlandPool.size() > 1) {
            return this.mainlandPool.get(1);
        }
        return this.mainlandPool.get(0);
    }

    /** 石头滩群系（左右两侧悬崖海岸用，懒加载；找不到时退回沙滩） */
    private Holder<Biome> stonyShore() {
        if (!this.stonyShoreChecked) {
            this.stonyShoreChecked = true;
            this.stonyShoreCache = findBiome("minecraft:stony_shore", this.mainlandPool.get(BEACH));
        }
        return this.stonyShoreCache;
    }

    public BiomeSource delegate() {
        return this.delegate;
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

    public List<Holder<Biome>> islandPool() {
        return this.islandPool;
    }

    public List<Holder<Biome>> mainlandPool() {
        return this.mainlandPool;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(
            this.delegate.possibleBiomes().stream(),
            Stream.concat(this.mainlandPool.stream(), this.islandPool.stream())
        ).distinct();
    }

    /** 配置懒加载：首次调用（世界生成，晚于 ServerAboutToStart）时用静态变量填充 radius、cfg */
    private void ensureConfig() {
        if (this.cfg != null) return;
        // 【双保险】在第一次群系分配时同步配置（世界已存在，CAIConfig 必已加载）
        ContinentIslandField.ensureConfigLoaded();
        // 直接读 CAIConfig（不再依赖 ServerAboutToStart 的时序）
        try {
            this.radius = CAIConfig.RADIUS.get();
            this.transition = CAIConfig.TRANSITION.get();
            this.grid = CAIConfig.GRID.get();
            this.islandChance = CAIConfig.ISLAND_CHANCE.get();
        } catch (Exception ignored) {
            // 极端情况：回退 ContinentIslandField 的硬编码默认
            this.radius = ContinentIslandField.continentRadius;
            this.transition = ContinentIslandField.continentTransition;
            this.grid = ContinentIslandField.continentGrid;
            this.islandChance = ContinentIslandField.continentIslandChance;
        }
        this.cfg = new ContinentIslandField.Config(this.radius, this.transition, this.grid, this.islandChance);
        // 同时初始化外岛群系黑名单
        if (this.outerIslandBlacklist == null || this.outerIslandBlacklist.isEmpty()) {
            this.outerIslandBlacklist = new java.util.HashSet<>();
            try {
                for (String s : CAIConfig.OUTER_ISLAND_BIOME_BLACKLIST.get()) {
                    if (s == null) continue;
                    try {
                        ResourceLocation loc = ResourceLocation.parse(s.trim());
                        this.outerIslandBlacklist.add(loc);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
    }

    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        ensureConfig();
        double bx = x * 4.0;
        double bz = z * 4.0;
        ContinentIslandField.Config cfg = this.cfg;

        // 林地府邸固定点：周围 128×128 格区域强制黑森林群系
        // （府邸是 80×80 格建筑且只能建在黑森林中，给足环境避免落在其他群系）
        net.minecraft.world.level.ChunkPos mansionChunk = ContinentIslandField.woodlandMansionChunkPos();
        if (mansionChunk != null) {
            double mcx = mansionChunk.getMiddleBlockX();
            double mcz = mansionChunk.getMiddleBlockZ();
            if (Math.abs(bx - mcx) <= 64.0 && Math.abs(bz - mcz) <= 64.0) {
                return this.mainlandPool.get(12); // dark_forest
            }
        }

        // 必生成大湖（三个湖之一）
        double lake = ContinentIslandField.lakeValue(bx, bz, cfg);
        if (!Double.isNaN(lake)) {
            // 找到命中的湖
            int hit = -1;
            for (int i = 0; i < ContinentIslandField.LAKE_COUNT; i++) {
                if (!Double.isNaN(ContinentIslandField.lakeValueAt(i, bx, bz, cfg))) {
                    hit = i;
                    break;
                }
            }
            if (hit >= 0) {
                int type = ContinentIslandField.lakeType[hit];
                if (type == 0 && lake < 0) {
                    // 群系湖：湖面固定为该湖对应的群系（含海洋与陆地群系，逻辑与陆地群系一致），岛屿仍委托原版
                    int biomeIdx = LAKE_BIOME_MAP[ContinentIslandField.lakeBiomeIndex[hit]];
                    // 非雪原/山脉扇区禁止冰冻海洋湖面（含雪/冰）
                    if (biomeIdx == 22 && !allowSnow(bx, bz)) {
                        biomeIdx = 20; // 改为温水海洋
                    }
                    return this.mainlandPool.get(biomeIdx);
                }
                if (type == 1) {
                    // 深湖：湖面统一深海、湖岸陆地统一樱花树林——不再委托原版多噪声
                    // （原版多噪声会把湖盆大陆度约 0.25 判成陆地群系，导致"深湖带森林/草甸"）
                    // 山湖周围一圈樱花树林，与山峰扇区的樱花点缀呼应
                    double b = ContinentIslandField.bias(bx, bz, cfg);
                    return b >= ContinentIslandField.LAND_BIAS_THRESHOLD
                        ? this.mainlandPool.get(CHERRY_GROVE)
                        : this.mainlandPool.get(DEEP_OCEAN);
                }
                if (type == 2 && lake < 0) {
                    // 岛湖水面：固定普通海洋（湖中岛屿仍委托原版多噪声）
                    return this.mainlandPool.get(ISLAND_SECTOR_OCEAN);
                }
            }
            // 岛湖/群系湖的岛屿：委托原版多噪声（水与岛群系自然给出），并过滤含雪群系
            Holder<Biome> delegateBiome = this.delegate.getNoiseBiome(x, y, z, sampler);
            return (this.isExcluded(delegateBiome) || (isSnowy(delegateBiome) && !allowSnow(bx, bz)))
                ? this.pickMainlandBiome(x, y, z, sampler)
                : delegateBiome;
        }
        // ===== 群岛-环山带过渡湿地浅滩带（0.80R~0.98R，限群岛扇区角度）=====
        // 湿地带地形由 ArchipelagoWetland 独立计算拉向目标高度（Y≈62），
        // 本处群系判定与地形共用 archipelagoWetlandBand，保证严格对齐。
        // 【只进不退策略】沼泽只向内海方向延伸，绝不被海洋/其他群系侵入。
        // 边缘用噪声控制沼泽延伸程度——有的地方沼泽多进、有的地方少进，形成犬牙交错。
        double wetBand = ContinentIslandField.archipelagoWetlandBand(bx, bz, this.radius);
        if (wetBand > 0.01) {
            logWetlandSample(bx, bz, wetBand);
            if (wetBand >= 0.10) {
                // 湿地带主体：100% 沼泽/红树林
                double wr = ContinentIslandField.valueNoise(bx, bz, 180, 9101);
                return wr < 0.45
                    ? this.mainlandPool.get(MANGROVE_SWAMP)
                    : this.mainlandPool.get(SWAMP);
            }
            // 带边缘（0.01~0.10）：只进不退
            double baseT = (wetBand - 0.01) / 0.09;
            double noise = ContinentIslandField.valueNoise(bx, bz, 40, 9102);
            double swampProb = Math.min(1.0, baseT + noise * (1.0 - baseT) * 0.8);
            if (swampProb > 0.25) {
                double wr = ContinentIslandField.valueNoise(bx, bz, 180, 9101);
                return wr < 0.45
                    ? this.mainlandPool.get(MANGROVE_SWAMP)
                    : this.mainlandPool.get(SWAMP);
            }
            return this.mainlandPool.get(ISLAND_SECTOR_OCEAN);
        }
        double dist = Math.sqrt(bx * bx + bz * bz);
        if (dist < this.radius) {
            ContinentIslandField.Config cfgIsl = this.cfg;
            // 群岛扇区：过渡带由 islandSectorFalloff（宽空间场）驱动，窗口 0.05 < falloff <= 0.34
            // 与 ContinentIslandField.bias 的过渡权重 extW 完全对齐 → 群系和地形 1:1 匹配。
            // 此窗口委托原版多噪声源，用实际气候参数（continents/erosion/offset）判定群系，
            // 陆地 → 沙滩 → 浅海 → 深海 自然渐变，没有断崖、没有草地夹沙海错位。
            double islExtHere = ContinentIslandField.islandSectorFalloff(bx, bz, cfgIsl);
            // 两侧海岸：不做任何人工干预。angMask 在群岛扇区外强制=0 → islExtHere=0，
            // 自然落入下方 pickMainlandBiome（原版大陆群系），形成标准 MC 自然海岸线。
            if (islExtHere > 0.05) {
                if (islExtHere <= 0.34) {
                    // ===== 沙滩带（falloff 0.05~0.34）=====
                    // 与 ArchipelagoTransition 的压低平坦段对齐：整个压低区域压到 Y64 浅滩，
                    // 固定 beach 群系；内海方向大幅扩展，不再有"沙-海"缓冲带与草地错位。
                    // 【沙滩带角度收窄】只在 delta < half*0.75（15°）内强制沙滩；
                    // 15°~20° 之间压低带角度渐入已把地形回升到大陆高度，
                    // 群系随之走大陆群系，保持"地形/群系"一致，避免两侧高地贴沙滩。
                    double bAngle = Math.atan2(bz, bx);
                    double bCenter = ContinentIslandField.sectorCenterAngle(ContinentIslandField.ISLAND_SECTOR);
                    double bDelta = Math.abs(Math.atan2(Math.sin(bAngle - bCenter), Math.cos(bAngle - bCenter)));
                    double bHalf = ContinentIslandField.islandSectorHalfRad(); // 含群岛专用扩展，沙滩带跟随群岛扇区向两侧扩大
                    if (bDelta < bHalf * 0.75) {
                        return this.mainlandPool.get(BEACH);
                    }
                    // 两侧收窄区（delta >= 15°）：地形已回升大陆高度，走大陆群系
                    return this.pickMainlandBiome(x, y, z, sampler);
                }
                // falloff > 0.34：群岛内部正常群系（小岛/内海）
                return this.pickMainlandBiome(x, y, z, sampler);
            }
            // 大陆核心：少量群系、每种占大片面积（大尺度气候噪声驱动，地形起伏由噪声路由保证）
            return this.pickMainlandBiome(x, y, z, sampler);
        }
        boolean land = ContinentIslandField.bias(bx, bz, cfg) >= ContinentIslandField.LAND_BIAS_THRESHOLD;
        // 海岸带（R <= dist < R+transition）命中外海岛几何时也走 pickIslandBiome：
        // 岛心偏移 ±0.60 格 + 半径缩放可让外海岛向内「溢入」过渡带约 400 格，
        // 若不拦截，溢出部分会被委托原版多噪声，同一岛屿被拆成多群系拼贴。
        boolean outerLandBleed = land
            && dist >= this.radius
            && dist < this.radius + this.transition
            && ContinentIslandField.isOuterIslandLand(bx, bz, cfg);
        if (((dist >= this.radius + this.transition && land) || outerLandBleed) && !this.islandPool.isEmpty()) {
            // 外围岛屿（含溢入海岸带的部分）：每个岛屿固定一个群系
            return this.pickIslandBiome(bx, bz, cfg);
        }
        // 超大陆、海岸带、外围深海：全部委托原版多噪声源。
        // 沙滩不再强制生成——原版根据 continents/erosion/offset 等气候参数自主出现，
        // 位置、宽度自然贴合地形，不会出现错位（草地带夹沙海）。
        // 禁雪只限超大陆（dist < radius+transition）；外围深海/岛屿不禁雪
        Holder<Biome> delegateBiome = this.delegate.getNoiseBiome(x, y, z, sampler);
        boolean snowBan = dist < this.radius + this.transition;
        Holder<Biome> resolved = (this.isExcluded(delegateBiome) || (snowBan && isSnowy(delegateBiome) && !allowSnow(bx, bz)))
            ? this.pickMainlandBiome(x, y, z, sampler)
            : delegateBiome;
        if (dist >= this.radius + this.transition && land && isOuterIslandBlacklisted(resolved)) {
            return outerIslandFallback();
        }
        return resolved;
    }

    /** 湿地带采样日志（限 10 次）：打印湿地实际出现位置的角度与群岛扇区中心角，
     *  用于核对"湿地带限群岛扇区角度"是否在运行时真正生效 */
    private static void logWetlandSample(double bx, double bz, double band) {
        if (wetlandLogCount >= 10) {
            return;
        }
        wetlandLogCount++;
        double ang = Math.toDegrees(Math.atan2(bz, bx));
        double center = Math.toDegrees(ContinentIslandField.sectorCenterAngle(ContinentIslandField.ISLAND_SECTOR));
        LOGGER.info("WETLAND sample: x={}, z={}, angle={}°, islandSectorCenter={}°, band={}",
            (int) bx, (int) bz,
            String.format(java.util.Locale.ROOT, "%.1f", ang),
            String.format(java.util.Locale.ROOT, "%.1f", center),
            String.format(java.util.Locale.ROOT, "%.3f", band));
    }

    /** 剔除群系判定：冰刺平原、恶地（含变种）、风袭系不在超大陆生成 */
    private boolean isExcluded(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> EXCLUDED_MAINLAND.contains(key.location()))
            .orElse(false);
    }

    /** 该群系是否含雪（雪原/冰封类）——非雪原扇区、非山脉扇区禁止出现 */
    private boolean isSnowy(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> {
                String p = key.location().getPath();
                return p.contains("snowy") || p.contains("frozen") || p.contains("ice_spikes") || p.equals("grove");
            })
            .orElse(false);
    }

    /** 该位置是否允许雪群系：
     *  - 雪原扇区（扇区5）与山脉扇区（扇区0）→ 允许（含两扇区内的环山带）
     *  - 群岛扇区（扇区2）岛屿区（islandSectorFalloff ≥ 0.40 且远离湿地带）→ 允许
     *    让群岛内部高大岛屿可自然生成寒冷群系
     *  - 其他扇区（丛林/沙漠/热草）或 非山脉/雪原扇区角度下的环山带 → 一律禁止
     *  - 湿地带（archipelagoWetlandBand > 0.01）→ 在调用方单独处理（替换为湿地群系） */
    private boolean allowSnow(double px, double pz) {
        ContinentIslandField.Config cfg = this.cfg;
        // 先判断角度：只在雪原扇区(5)或山脉扇区(0)的楔形范围内才允许下雪
        // 这样无论 dist 是 0.95R 以内还是以外，群岛/丛林/沙漠/热草扇区的环山带都不会出现雪群系
        double snowMask = ContinentIslandField.sectorMask(5, px, pz, cfg);
        double mountainMask = ContinentIslandField.sectorMask(0, px, pz, cfg);
        // 角度判定（不 warp，纯几何角度差）用于 dist>0.95R 的环山带雪控制，
        // 彻底杜绝 sectorMask 角度扭曲/径向交叠造成的"边缘漏网"：
        // 只有精确位于扇区 0(山脉)/5(雪原) 的半宽 ±20°(不扭曲)之内，环山带才允许雪。
        double angle = Math.atan2(pz, px);
        double snowCenter = ContinentIslandField.sectorCenterAngle(5);
        double mountainCenter = ContinentIslandField.sectorCenterAngle(0);
        double deltaSnow = Math.abs(Math.atan2(Math.sin(angle - snowCenter), Math.cos(angle - snowCenter)));
        double deltaMountain = Math.abs(Math.atan2(Math.sin(angle - mountainCenter), Math.cos(angle - mountainCenter)));
        double half = Math.toRadians(ContinentIslandField.sectorHalfWidthDeg);
        boolean inSnowOrMountainSector = (snowMask > 0.05) || (mountainMask > 0.05);
        boolean ringInSnowOrMountainSector = (deltaSnow < half * 1.0) || (deltaMountain < half * 1.0);

        double mountHeight = ContinentIslandField.mountainValue(px, pz, this.radius);
        double dist = Math.sqrt(px * px + pz * pz);

        // 环山区（0.95R 以外）：仅雪原/山脉扇区角度内 + 山高足够 + 噪声打破完美环形，才落雪；
        // 额外豁免：群岛扇区内的高大岛屿（islExt>=0.40 且非湿地带）也可寒冷群系（单岛单群系、含雪允许）
        if (dist > this.radius * 0.95) {
            if (!ringInSnowOrMountainSector) {
                // 非雪原/山脉扇区角度 → 严格禁雪，除非是群岛扇区内部的岛屿区域（islExt>=0.40 且 不在湿地带）
                double islExt = ContinentIslandField.islandSectorFalloff(px, pz, cfg);
                if (islExt >= 0.40) {
                    double wet = ContinentIslandField.archipelagoWetlandBand(px, pz, this.radius);
                    return wet <= 0.01; // 仅非湿地带的群岛内部小岛允许雪
                }
                return false; // 丛林/沙漠/热草/群岛扇区的环山带 → 严格禁止雪
            }
            double ringNoise = ContinentIslandField.valueNoise(px, pz, 80, 8800);
            return mountHeight > 0.35 && ringNoise > 0.30;
        }

        // 0.95R 以内：雪原扇区 或 山脉扇区山高足够 → 允许
        if (snowMask > 0.35 || (mountainMask > 0.35 && mountHeight > 0.35)) {
            return true;
        }
        // 群岛扇区内部岛屿：允许寒冷群系，但湿地带除外
        double islExt = ContinentIslandField.islandSectorFalloff(px, pz, cfg);
        if (islExt >= 0.40) {
            double wet = ContinentIslandField.archipelagoWetlandBand(px, pz, this.radius);
            return wet <= 0.01; // 岛区（非湿地带）允许雪
        }
        return false;
    }

    /** 超大陆中剔除的群系：冰刺平原、恶地（含变种）、风袭系。海岸带委托原版时也可能给出，需过滤 */
    private static final Set<ResourceLocation> EXCLUDED_MAINLAND = Set.of(
        ResourceLocation.withDefaultNamespace("ice_spikes"),
        ResourceLocation.withDefaultNamespace("badlands"),
        ResourceLocation.withDefaultNamespace("eroded_badlands"),
        ResourceLocation.withDefaultNamespace("wooded_badlands"),
        ResourceLocation.withDefaultNamespace("windswept_gravelly_hills"),
        ResourceLocation.withDefaultNamespace("windswept_hills"),
        ResourceLocation.withDefaultNamespace("windswept_forest")
    );

    /**
     * 群系湖可选群系 → mainlandPool 索引映射（海洋与陆地群系均可作为湖面，逻辑与陆地群系一致）：
     * 0=温水海洋, 1=温水海洋, 2=冷水海洋, 3=冰冻海洋,
     * 4=沼泽, 5=红树林沼泽, 6=丛林, 7=竹林, 8=蘑菇岛, 9=樱花树林
     */
    private static final int[] LAKE_BIOME_MAP = { 19, 20, 21, 22, 6, 17, 4, 16, 24, 23 };

    /** 群岛扇区（原沼泽位置）的岛间海面群系索引（mainlandPool 中的普通海洋） */
    private static final int ISLAND_SECTOR_OCEAN = 25;

    /** 沼泽群系索引（mainlandPool 中的 swamp，群岛-环山带过渡湿地带用） */
    private static final int SWAMP = 6;

    /** 红树林沼泽群系索引（mainlandPool 中的 mangrove_swamp，湿地带外侧浅水用） */
    private static final int MANGROVE_SWAMP = 17;

    /** 樱花树林群系索引（mainlandPool 中的 cherry_grove） */
    private static final int CHERRY_GROVE = 23;

    /** 冰封峰顶群系索引（mainlandPool 中的 frozen_peaks，雪山真实化） */
    private static final int FROZEN_PEAKS = 26;

    /** 沙滩群系索引（mainlandPool 中的 beach，群岛扇区过渡带强制沙滩带用） */
    private static final int BEACH = 27;

    /** 深海群系索引（mainlandPool 中的 deep_ocean，海洋神殿保留区） */
    private static final int DEEP_OCEAN = 32;

    /** 河流群系索引（mainlandPool 中的 river，群岛过渡带河网用） */
    private static final int RIVER = 33;

    /** 原始桦木森林（桦木森林变种，更高的白桦树）索引 */
    private static final int OLD_GROWTH_BIRCH_FOREST = 34;

    /** 原始松木针叶林（针叶林变种）索引 */
    private static final int OLD_GROWTH_PINE_TAIGA = 35;

    /** 原始云杉针叶林（针叶林变种）索引 */
    private static final int OLD_GROWTH_SPRUCE_TAIGA = 36;

    /** 山脉扇区索引（山峰→山脉分级，扇区 0） */
    private static final int MOUNTAIN_SECTOR = 0;

    /** 群岛小岛可用群系全池缓存（含其他模组群系，剔除海洋类），惰性构建 */
    private List<Holder<Biome>> allLandBiomesCache;

    private Holder<Biome> pickMainlandBiome(int x, int y, int z, Climate.Sampler sampler) {
        double px = x * 4.0;
        double pz = z * 4.0;
        double dist = Math.sqrt(px * px + pz * pz);
        double angle = Math.atan2(pz, px);
        List<Holder<Biome>> pool = this.mainlandPool;
        ContinentIslandField.Config cfg = this.cfg;

        // 计算 6 个扇区的平滑 mask，取最强与次强（扇区边界用宽过渡带平滑衰减）。
        // 山脉扇区用 mountainValue（蜿蜒+峰谷结构），与 MountainSector 地形抬升完全一致
        double best = 0.0;
        double second = 0.0;
        int bestS = -1;
        int secondS = -1;
        for (int i = 0; i < 6; i++) {
            double m = (i == MOUNTAIN_SECTOR)
                ? ContinentIslandField.mountainValue(px, pz, this.radius)
                : ContinentIslandField.sectorMask(i, px, pz, cfg);
            if (m > best) {
                second = best;
                secondS = bestS;
                best = m;
                bestS = i;
            } else if (m > second) {
                second = m;
                secondS = i;
            }
        }

        // 无扇区覆盖（扇区间隙/环带外）→ 普通大陆群系
        if (bestS < 0 || best < 0.22) {
            return baseMainlandBiome(px, pz, dist, angle, pool);
        }

        // 最强扇区主导 → 该扇区群系
        if (best >= 0.62) {
            return sectorBiome(bestS, best, px, pz, dist, angle, pool, sampler);
        }

        // 过渡带：高频噪声按强度权重在最强/次强扇区间选择（较大斑块犬牙交错 → 宏观平滑渐变，减少切割感）
        double w = best / (best + second + 1.0E-9);
        double n = ContinentIslandField.valueNoise(px, pz, 64, 8080);
        int chosen = (n < w) ? bestS : secondS;
        double chosenMask = (n < w) ? best : second;
        if (chosen < 0) {
            chosen = bestS;
        }
        if (chosenMask < 0.22) {
            chosenMask = best;
        }
        return sectorBiome(chosen, chosenMask, px, pz, dist, angle, pool, sampler);
    }

    /** 按扇区返回群系：0=山脉分级，2=群岛，其余扇区按配置化的主/附属群系权重选取。
     *  非山脉/群岛扇区先尝试混入其他模组群系点缀（原版为主、模组为辅）。 */
    private Holder<Biome> sectorBiome(int sector, double mask, double px, double pz, double dist, double angle, List<Holder<Biome>> pool, Climate.Sampler sampler) {
        if (sector == ContinentIslandField.ISLAND_SECTOR) {
            Holder<Biome> base = archipelagoBiome(mask, px, pz, dist, angle, pool);
            // 群岛扇区配置的附属群系作为额外点缀（陆地/浅水，不影响海洋神殿保留区）
            if (!ContinentIslandField.isInMonumentClear(px, pz)) {
                SectorBiomeData sd = getSectorBiomeData()[sector];
                if (sd != null && !sd.extras().isEmpty()) {
                    double r = ContinentIslandField.valueNoise(px, pz, 210, 52002);
                    double[] cum = sd.extrasCumulative();
                    for (int i = 0; i < cum.length; i++) {
                        if (r < cum[i]) return sd.extras().get(i);
                    }
                }
            }
            return base;
        }
        if (sector == MOUNTAIN_SECTOR) {
            Holder<Biome> base = mountainRangeBiome(mask, px, pz, pool);
            // 山脉扇区配置的附属群系：只在山脚/山谷（mask 较低）叠加，不抢峰顶群系
            if (mask < 0.40) {
                SectorBiomeData sd = getSectorBiomeData()[sector];
                if (sd != null && !sd.extras().isEmpty()) {
                    double r = ContinentIslandField.valueNoise(px, pz, 220, 52000);
                    double[] cum = sd.extrasCumulative();
                    for (int i = 0; i < cum.length; i++) {
                        if (r < cum[i]) return sd.extras().get(i);
                    }
                }
            }
            return base;
        }
        // 非山脉/群岛扇区：自动发现的模组群系先点缀（~12%），然后再走配置化的主/附属权重
        Holder<Biome> extra = modExtraBiome(px, pz);
        if (extra != null) {
            extra = filterConfiguredBiome(extra, sector, px, pz, pool);
        }
        // 沙漠扇区专属：BOP outback 作为附属群系（少量小片点缀，约占 8%）。
        // 阈值 0.92 + 尺度 220 → 稀疏小斑块；不会像恶地那样大范围铺开，沙漠仍为主体。
        if (sector == 3) {
            Holder<Biome> outback = bopOutback();
            if (outback != null && ContinentIslandField.valueNoise(px, pz, 220, 9101) > 0.92) {
                return outback;
            }
        }
        Holder<Biome> configured = pickConfiguredSectorBiome(sector, px, pz, pool.get(FALLBACK_SECTOR_MAIN[sector]));
        configured = filterConfiguredBiome(configured, sector, px, pz, pool);
        // 雪原扇区：针叶林/冰刺只在扇区内部（mask 高）生成，边缘回落雪原主群系
        if (sector == 5 && mask < 0.45 && isSnowSectorExtra(configured)) {
            SectorBiomeData sd = getSectorBiomeData()[sector];
            configured = (sd != null && sd.main() != null) ? sd.main() : pool.get(FALLBACK_SECTOR_MAIN[sector]);
        }
        return (extra != null) ? extra : configured;
    }

    /** 是否为雪原扇区的专属附属群系（snowy_taiga / ice_spikes）——只在雪原内部生成 */
    private boolean isSnowSectorExtra(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> {
                String p = key.location().getPath();
                return p.equals("snowy_taiga") || p.equals("ice_spikes");
            })
            .orElse(false);
    }

    /**
     * 配置化扇区群系安全过滤：防止错误扇区出现冰刺/恶地/风袭系/含雪群系（除非该扇区明确允许）。
     * 不通过时替换为该扇区配置的主群系（再不行走 fallback）。
     */
    private Holder<Biome> filterConfiguredBiome(Holder<Biome> biome, int sector, double px, double pz, List<Holder<Biome>> pool) {
        if (biome == null) return null;
        boolean needFix = false;
        // 剔除群系：只有在"允许的扇区"里才放行
        if (isExcluded(biome)) {
            boolean allowed = false;
            var key = biome.unwrapKey();
            if (key.isPresent()) {
                String path = key.get().location().getPath();
                // 恶地家族 → 只允许沙漠扇区（3）
                if (path.contains("badlands") && sector == 3) allowed = true;
                // 冰刺平原 → 只允许雪原扇区（5）
                if (path.equals("ice_spikes") && sector == 5) allowed = true;
                // 风袭系：所有扇区都排除（超大陆硬约束）
            }
            if (!allowed) needFix = true;
        }
        // 含雪群系：只允许雪原（5）/ 山脉（0）且 allowSnow 通过
        if (!needFix && isSnowy(biome) && !allowSnow(px, pz)) needFix = true;
        // 积雪山坡：禁止在雪原扇区（5）和山脉扇区（0）生成（群岛不受限）
        if (!needFix && (sector == 5 || sector == 0)) {
            var slKey = biome.unwrapKey();
            if (slKey.isPresent() && slKey.get().location().getPath().equals("snowy_slopes")) {
                needFix = true;
            }
        }
        if (!needFix) return biome;
        SectorBiomeData sd = getSectorBiomeData()[sector];
        if (sd != null && sd.main() != null) return sd.main();
        return pool.get(FALLBACK_SECTOR_MAIN[sector]);
    }

    /**
     * 群岛扇区：核心区小岛随机群系（全群系池，不受温度影响）、岛间内海。
     * <p>
     * 群岛扇区：内海 + 小岛；沙滩不强制，由系统自主生成（原版多噪声源 + 地形自然配合）。
     */
    private Holder<Biome> archipelagoBiome(double mask, double px, double pz, double dist, double angle, List<Holder<Biome>> pool) {
        // ===== 海洋神殿保留区：一整片深海（deep ocean）=====
        if (ContinentIslandField.isInMonumentClear(px, pz)) {
            return pool.get(DEEP_OCEAN);
        }
        ContinentIslandField.Config cfg = this.cfg;

        // ===== 分支1：群岛小岛陆地 =====
        // 内部小岛就是岛屿群系（蘑菇岛等），岸边直接入海，沙滩系统自主决定
        // 【群岛比内海小一圈】与地形层 bias() 的 w2 缓冲完全一致：内海边缘缓冲环
        // （islExt <= 0.20）内地形不回升（bias 恒 -0.65 深海），群系层同样不给小岛群系，
        // 避免内海边缘"地形是水、群系是小岛"的错位（其他群系跑上内海边缘的陆地）。
        if (ContinentIslandField.islandSectorIsLand(px, pz)) {
            double bufExt = ContinentIslandField.islandSectorFalloff(px, pz, cfg);
            double bufMask = ContinentIslandField.islandSectorMask(px, pz, cfg);
            double bufW = (bufExt > 0.20)
                ? Mth.smoothstep((float) Mth.clamp((bufMask - 0.45) / 0.40, 0.0, 1.0))
                : 0.0;
            if (bufW > 0.0) {
                return randomIslandBiome(px, pz);
            }
        }

        // ===== 分支2：混合水陆判定 =====
        double finalBias = ContinentIslandField.bias(px, pz, cfg);
        boolean isLand = finalBias >= ContinentIslandField.LAND_BIAS_THRESHOLD;
        if (isLand) {
            // 陆地：正常陆地群系（平原/森林/沼泽等），沙滩不再强制，自主生成
            return baseMainlandBiome(px, pz, dist, angle, pool);
        }

        // ===== 分支3：水域 =====
        // 【2026-08-22 注释掉】过渡带河网 biome 判定：
        //   地形层 bias() 的过渡带河网侵蚀（挖掘河道）已整体删除，过渡带现已为纯线性
        //   base→-0.65 海平面，海床平坦；继续在 biome 层强制贴 RIVER 群系会形成"幽灵
        //   河流"——平坦海面上贴出河流群系的水色条带，并在 RIVER 群系边界自动生成沙
        //   滩块，导致过渡水域出现与实际地形不匹配的奇怪沙滩/水色。先注释观察效果，
        //   后续若恢复河道地形再同步解注释此处。
        /*
        // 过渡带河网优先：外缘浅海的密细河道显示河流群系
        double trans = ContinentIslandField.islandTransitionWeight(px, pz, cfg);
        if (trans > 0.02 && ContinentIslandField.islandTransitionRiver(px, pz) > 0.5) {
            return pool.get(RIVER);
        }
        */
        // 其余水域一律内海；沙滩由原版自主生成
        return pool.get(ISLAND_SECTOR_OCEAN);
    }

    /**
     * 山脉扇区（模拟真实山脉）：按与地形完全一致的结构值（mountainValue，即参数 mask）分层。
     * 结构值由蜿蜒 mask × 峰谷结构决定，与 MountainSector 的地形抬升数值完全相同——
     * 群系永远跟实际山高走，不会错位。
     * <ul>
     *   <li>峰顶（结构值高）：按温度真实分带——寒=冰封峰顶、
     *       温=积雪斑驳（细节噪声决定这座峰有没有雪）、热=秃岩峰（原版热带高山那样）</li>
     *   <li>山腰：高山草甸 / 山坡针叶林（积雪山坡已禁止）</li>
     *   <li>山脚/山谷：草甸/针叶林/森林（谷地结构值低，自然回落低地群系）</li>
     * </ul>
     */
    private Holder<Biome> mountainRangeBiome(double mask, double px, double pz, List<Holder<Biome>> pool) {
        double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
        if (mask > 0.55) {
            // 峰顶：温度决定雪线高低，细节噪声让积雪斑驳（真实雪山：有的峰有雪、有的露岩）
            double snow = ContinentIslandField.valueNoise(px, pz, 64, 6006);
            if (temp < 0.48) return pool.get(FROZEN_PEAKS);                  // 寒：冰封峰顶（禁止积雪山坡）
            if (temp < 0.72) return snow > 0.52 ? pool.get(1) : pool.get(0);  // 温：积雪斑驳（尖峭雪顶/裸岩）
            return pool.get(0);                                               // 热：秃岩峰
        }
        // 樱花树林点缀（山脚/山腰的低海拔区）
        double cherry = ContinentIslandField.valueNoise(px, pz, 90, 5005);
        if (cherry > 0.80 && mask < 0.48) {
            return pool.get(CHERRY_GROVE);
        }
        if (mask > 0.36) {
            // 山腰：雪线只出现在高寒段，其余是高山草甸/山坡针叶林（不是整片雪白）
            if (temp < 0.66) return pool.get(13);  // 高山草甸（禁止积雪山坡）
            return taigaWithVariants(px, pz, pool); // 山坡针叶林（含原始变种）
        }
        // 山脚/山谷：气候驱动的低地群系
        if (temp < 0.30) return taigaWithVariants(px, pz, pool); // 针叶林（含原始变种）
        if (temp < 0.60) return pool.get(13);  // 草甸
        return pool.get(10);                    // 森林
    }

    /**
     * 普通大陆群系：大尺度温度/湿度噪声驱动的平原/森林变体 + 少量小斑块（竹林/红树林/石岸）+ 可选边缘环山。
     */
    private Holder<Biome> baseMainlandBiome(double px, double pz, double dist, double angle, List<Holder<Biome>> pool) {
        // 边缘环山带（配置默认开启）：山峰系群系，与 RingMountain 地形抬升带（0.97R~1.0R）对齐，
        // 群系从 0.95R 开始（略提前于地形抬升，保证过渡自然）
        if (ContinentIslandField.ringMountainEnabled && dist > this.radius * 0.95) {
            double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
            if (temp < 0.35) return pool.get(2);  // 冰封山峰
            if (temp < 0.60) return pool.get(0);  // 裸岩山峰
            return pool.get(1);                    // 尖峭山峰
        }

        double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
        double humid = ContinentIslandField.valueNoise(px, pz, 400, 808);

        // 小斑块：直径约 25~100 格，少量点缀（同一种子噪声，按气候条件分流）
        double spot = ContinentIslandField.valueNoise(px, pz, 55, 1001);
        if (spot > 0.88) {
            // 竹林：只在丛林扇区（扇区 1）30° 范围内生成，降低概率
            double jungleCenter = ContinentIslandField.sectorCenterAngle(1);
            double angleDelta = Math.abs(Math.atan2(Math.sin(angle - jungleCenter), Math.cos(angle - jungleCenter)));
            if (angleDelta < Math.toRadians(30.0) && temp > 0.45 && temp < 0.75 && humid > 0.55) {
                return pool.get(16);   // 竹林
            }
            // 红树林/沼泽不再在普通大陆随机生成——作为丛林扇区（雨林）的附属群系生成
            if (dist > this.radius * 0.85) return pool.get(18);                      // 石岸
        }

        // 樱花树林：只在中心核心区域（dist < 0.30R）稀疏小面积生成（类似竹林的少量点缀逻辑）
        if (dist < this.radius * 0.30) {
            double cherrySpot = ContinentIslandField.valueNoise(px, pz, 90, 1004);
            if (cherrySpot > 0.86 && temp > 0.50 && temp < 0.85) {
                return pool.get(CHERRY_GROVE);  // 樱花树林
            }
        }

        // 温带/大陆气候：平原类与森林类群系总体约 1:1
        if (temp < 0.30) {
            // 寒冷：针叶林与平原（寒带平原）约各半；针叶林混入原始针叶林变种
            if (humid < 0.40) return pool.get(9);   // 平原
            return taigaWithVariants(px, pz, pool); // 针叶林（含变种）
        }
        if (temp < 0.60) {
            // 温带：平原为主（0.45 内），森林/桦木/黑森林按湿度递增
            if (humid < 0.45) {
                // 平原偶尔混入向日葵平原（平原变种，约 10%）
                if (ContinentIslandField.valueNoise(px, pz, 260, 3003) < 0.10) {
                    return pool.get(15);
                }
                return pool.get(9);   // 平原
            }
            if (humid < 0.75) {
                // 森林：中央核心区小尺度混入桦木/黑森林小块，避免整片森林过于单调
                if (dist < this.radius * 0.35) {
                    double mix = ContinentIslandField.valueNoise(px, pz, 70, 3004);
                    if (mix < 0.25) return birchWithVariants(px, pz, pool); // 小块桦木（含变种）
                    if (mix > 0.85) return pool.get(12);                    // 小块黑森林
                }
                return pool.get(10);  // 森林
            }
            if (humid < 0.90) {
                return birchWithVariants(px, pz, pool); // 桦木林（含原始桦木变种）
            }
            return pool.get(12);      // 黑森林
        }
        if (temp < 0.85) {
            // 暖温带：草甸/森林/繁花森林，中央核心区同样小尺度混杂
            if (humid < 0.40) return pool.get(13);  // 草甸
            if (humid < 0.70) {
                if (dist < this.radius * 0.35) {
                    double mix = ContinentIslandField.valueNoise(px, pz, 70, 3006);
                    if (mix < 0.25) return birchWithVariants(px, pz, pool); // 小块桦木
                    if (mix > 0.85) return pool.get(14);                    // 小块繁花森林
                }
                return pool.get(10);  // 森林
            }
            return pool.get(14);       // 繁花森林
        }
        return pool.get(15); // 向日葵平原
    }

    /** 针叶林（含变种）：原始松木/原始云杉针叶林约 30% 概率替换普通针叶林（更巨大、更高的针叶树） */
    private Holder<Biome> taigaWithVariants(double px, double pz, List<Holder<Biome>> pool) {
        double v = ContinentIslandField.valueNoise(px, pz, 260, 3002);
        if (v < 0.30) {
            return v < 0.15 ? pool.get(OLD_GROWTH_PINE_TAIGA) : pool.get(OLD_GROWTH_SPRUCE_TAIGA);
        }
        return pool.get(8);
    }

    /** 桦木林（含变种）：原始桦木森林（显著更高的白桦树）约 25% 概率替换普通桦木林 */
    private Holder<Biome> birchWithVariants(double px, double pz, List<Holder<Biome>> pool) {
        if (ContinentIslandField.valueNoise(px, pz, 260, 3005) < 0.25) {
            return pool.get(OLD_GROWTH_BIRCH_FOREST);
        }
        return pool.get(11);
    }

    /** 群岛小岛群系：从全部可能群系（含其他模组）哈希随机选，不受温度影响；必生成一个蘑菇岛 */
    private Holder<Biome> randomIslandBiome(double px, double pz) {
        if (ContinentIslandField.islandMushroomCell(px, pz)) {
            return this.mainlandPool.get(24); // mushroom_fields
        }
        List<Holder<Biome>> all = allLandBiomes();
        if (all.isEmpty()) {
            return this.mainlandPool.get(13); // 兜底：草甸
        }
        // 必须用「所有者格」哈希而非当前格：岛心偏移 ±0.40 格（≈±120 格）
        // 会让同一岛屿横跨 2~3 个网格单元，按当前格哈希会把一岛切成多群系拼贴。
        long[] owner = ContinentIslandField.innerIslandOwner(px, pz);
        double h = ContinentIslandField.hash(owner[0], owner[1], 12345);
        int idx = (int) (h * all.size());
        return all.get(Math.min(idx, all.size() - 1));
    }

    /** 群岛小岛群系池：delegate 的所有可能群系剔除海洋/河流/海滩/蘑菇岛类（蘑菇岛只保留强制生成的一个）。
     *  含雪群系不剔除——群岛扇区不受雪系限制（与 {@link #allowSnow} 的群岛高岛豁免一致），
     *  小岛可随机到雪系群系 */
    private List<Holder<Biome>> allLandBiomes() {
        if (this.allLandBiomesCache == null) {
            this.allLandBiomesCache = this.delegate.possibleBiomes().stream()
                .filter(h -> !isOceanOrBeach(h))
                .distinct()
                .collect(Collectors.toList());
        }
        return this.allLandBiomesCache;
    }

    private boolean isOceanOrBeach(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> {
                String p = key.location().getPath();
                return p.contains("ocean") || p.contains("beach") || p.equals("river")
                    || p.equals("mushroom_fields") || p.equals("deep_dark");
            })
            .orElse(false);
    }

    /** 是否为原版（minecraft 命名空间）群系 */
    private boolean isVanillaBiome(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().getNamespace().equals("minecraft"))
            .orElse(true);
    }

    /** 其他模组群系缓存（非 minecraft 命名空间的陆生群系），惰性构建 */
    private List<Holder<Biome>> modBiomesCache;

    /** Biomes O' Plenty 的 outback：懒加载检测（模组未加载返回 null）。 */
    private Holder<Biome> bopOutback() {
        if (!this.bopOutbackChecked) {
            this.bopOutbackChecked = true;
            this.bopOutbackCache = findBiome("biomesoplenty:outback", null);
        }
        return this.bopOutbackCache;
    }

    /** 是否为 Biomes O' Plenty 的 outback 群系 */
    private boolean isBopOutback(Holder<Biome> biome) {
        return biome.unwrapKey()
            .map(key -> key.location().getNamespace().equals("biomesoplenty") && key.location().getPath().equals("outback"))
            .orElse(false);
    }

    /**
     * 其他模组群系池：delegate possibleBiomes + mainlandPool 中所有非原版命名空间的陆生群系。
     * 无其他群系模组时为空（扇区保持纯原版）；有其他模组时，扇区会以少量比例混入其群系。
     * outback 不在池中：它有专属的沙漠扇区附属群系通道（与恶地相同占比）。
     */
    private List<Holder<Biome>> modBiomes() {
        if (this.modBiomesCache == null) {
            this.modBiomesCache = Stream.concat(
                    this.delegate.possibleBiomes().stream(),
                    this.mainlandPool.stream()
                )
                .filter(h -> !isVanillaBiome(h))
                .filter(h -> !isOceanOrBeach(h))
                .filter(h -> !isSnowy(h))
                .filter(h -> !isExcluded(h))
                .filter(h -> !isBopOutback(h))
                .distinct()
                .collect(Collectors.toList());
        }
        return this.modBiomesCache;
    }

    /**
     * 扇区群系混入其他模组群系点缀：以原版群系为主，约 12% 概率混入一个模组群系
     * （类似沙漠中加恶地，但占比更小）。无其他模组群系时返回 null → 扇区纯原版。
     */
    private Holder<Biome> modExtraBiome(double px, double pz) {
        List<Holder<Biome>> mods = modBiomes();
        if (mods.isEmpty()) {
            return null;
        }
        if (ContinentIslandField.valueNoise(px, pz, 320, 7700) < 0.12) {
            double h = ContinentIslandField.valueNoise(px, pz, 180, 7701);
            int idx = (int) (h * mods.size());
            return mods.get(Math.min(idx, mods.size() - 1));
        }
        return null;
    }

    /** 每个岛屿固定一个群系：通过 3×3 邻域搜索找到"真正生成该岛的网格单元"
     *  （因为岛中心可偏移 ±0.60 格漂进相邻格，直接用当前格哈希会把同一岛拆成多段群系）。
     *  找到所属格后按哈希从岛群系池中确定性选取；命中外岛黑名单则向后扫描兜底。 */
    private Holder<Biome> pickIslandBiome(double bx, double bz, ContinentIslandField.Config cfg) {
        // 所有者格直接由 farIslandOwner 统一计算（best-value 准则，与 bias() 外围岛屿段完全一致），
        // 不再用最近中心反推——同一岛的所有点统一到一个 cx/cz，哈希唯一 → 群系唯一。
        long[] owner = ContinentIslandField.farIslandOwner(bx, bz, cfg);
        long ownerCx = owner[0];
        long ownerCz = owner[1];
        double h = ContinentIslandField.hash(ownerCx, ownerCz, 707);
        int n = this.islandPool.size();
        if (n == 0) return outerIslandFallback();
        int start = (int) (h * n) % n;
        if (!this.outerIslandBlacklist.isEmpty()) {
            int i = start;
            do {
                Holder<Biome> b = this.islandPool.get(i);
                if (!isOuterIslandBlacklisted(b)) return b;
                i = (i + 1) % n;
            } while (i != start);
            return outerIslandFallback();
        }
        return this.islandPool.get(start);
    }

    // ── 配置化扇区群系 ────────────────────────────────────────────────

    /** 从资源定位符字符串查找群系，优先 mainlandPool，其次 delegate 的所有可能群系 */
    private Holder<Biome> findBiome(String locStr, Holder<Biome> fallback) {
        if (locStr == null || locStr.isBlank()) return fallback;
        ResourceLocation loc;
        try {
            loc = ResourceLocation.parse(locStr.trim());
        } catch (Exception ex) {
            return fallback;
        }
        // 1) 在 mainlandPool 中按 key 精确匹配
        for (Holder<Biome> h : this.mainlandPool) {
            if (h.unwrapKey().map(k -> k.location().equals(loc)).orElse(false)) return h;
        }
        // 2) 在 delegate.possibleBiomes() 中按 key 匹配（支持其他模组群系）
        for (Holder<Biome> h : this.delegate.possibleBiomes()) {
            if (h.unwrapKey().map(k -> k.location().equals(loc)).orElse(false)) return h;
        }
        // 找不到（资源定位符写错 / 对应模组未加载）→ 回退到硬编码兜底
        return fallback;
    }

    /** 硬编码的扇区回退主群系（配置找不到群系时兜底），扇区索引 → mainlandPool 索引 */
    private static final int[] FALLBACK_SECTOR_MAIN = { 13, 4, 25, 3, 7, 5 };
    // 0山脉→meadow(13), 1丛林→jungle(4), 2群岛→ocean(25), 3沙漠→desert(3), 4热草→savanna(7), 5雪原→snowy_plains(5)

    /** 延迟获取 sectorBiomeData：第一次调用时才解析配置（避免在注册表加载阶段触发 delegate.possibleBiomes()） */
    private SectorBiomeData[] getSectorBiomeData() {
        if (this.sectorBiomeData == null) {
            this.sectorBiomeData = buildSectorBiomeData();
        }
        return this.sectorBiomeData;
    }

    /** 读取 6 个扇区的配置，构造 SectorBiomeData 数组 */
    private SectorBiomeData[] buildSectorBiomeData() {
        SectorBiomeData[] out = new SectorBiomeData[6];
        var mains = List.of(CAIConfig.SECTOR_0_MAIN, CAIConfig.SECTOR_1_MAIN, CAIConfig.SECTOR_2_MAIN,
                            CAIConfig.SECTOR_3_MAIN, CAIConfig.SECTOR_4_MAIN, CAIConfig.SECTOR_5_MAIN);
        var extras = List.of(CAIConfig.SECTOR_0_EXTRAS, CAIConfig.SECTOR_1_EXTRAS, CAIConfig.SECTOR_2_EXTRAS,
                             CAIConfig.SECTOR_3_EXTRAS, CAIConfig.SECTOR_4_EXTRAS, CAIConfig.SECTOR_5_EXTRAS);
        var weights = List.of(CAIConfig.SECTOR_0_EXTRA_WEIGHTS, CAIConfig.SECTOR_1_EXTRA_WEIGHTS, CAIConfig.SECTOR_2_EXTRA_WEIGHTS,
                              CAIConfig.SECTOR_3_EXTRA_WEIGHTS, CAIConfig.SECTOR_4_EXTRA_WEIGHTS, CAIConfig.SECTOR_5_EXTRA_WEIGHTS);

        for (int s = 0; s < 6; s++) {
            Holder<Biome> fb = this.mainlandPool.get(FALLBACK_SECTOR_MAIN[s]);
            Holder<Biome> main = findBiome(mains.get(s).get(), fb);

            List<? extends String> extrasRaw = extras.get(s).get();
            List<? extends Double> weightsRaw = weights.get(s).get();
            int n = Math.min(extrasRaw.size(), weightsRaw.size());
            List<Holder<Biome>> extrasList = new ArrayList<>(n);
            double[] rawW = new double[n];
            double sumW = 0.0;
            for (int i = 0; i < n; i++) {
                Holder<Biome> b = findBiome(extrasRaw.get(i), null);
                double w = Math.max(0.0, weightsRaw.get(i).doubleValue());
                if (b != null && w > 1.0e-6) {
                    extrasList.add(b);
                    rawW[extrasList.size() - 1] = w;
                    sumW += w;
                }
            }
            // 裁剪实际使用的长度（前面可能有找不到/权重为 0 被跳过的条目）
            int m = extrasList.size();
            double[] cum;
            if (m == 0) {
                cum = new double[0];
            } else {
                // 概率计算：主群系绝对概率 = 1 / (1 + sumW)
                // extras[i] 绝对概率 = w[i] / (1 + sumW)
                // 这样当 sumW≈0.29 时，主≈77%、附属合计≈23%（相对比例，不怕用户填和>1）
                double denom = 1.0 + sumW;
                cum = new double[m];
                double acc = 0.0;
                for (int i = 0; i < m; i++) {
                    acc += rawW[i] / denom;
                    cum[i] = acc;
                }
            }
            out[s] = new SectorBiomeData(main, extrasList, cum);
        }
        return out;
    }

    /**
     * 根据配置从指定扇区抽取一个群系（主/附属）。
     * 对扇区 0/2，这个方法只返回配置的"平地/兜底"群系；分级/内海逻辑由调用方单独跑。
     */
    private Holder<Biome> pickConfiguredSectorBiome(int sector, double px, double pz, Holder<Biome> fallback) {
        if (sector < 0 || sector >= getSectorBiomeData().length) return fallback;
        SectorBiomeData d = getSectorBiomeData()[sector];
        if (d == null) return fallback;
        if (d.extras().isEmpty()) return d.main() != null ? d.main() : fallback;

        // 雪原扇区（5）/丛林扇区（1）用更大噪声尺度 → 附属针叶林/冰刺/竹林等形成大片斑块，而非碎点
        int scale = (sector == 5 || sector == 1) ? 520 : 260;
        double r = ContinentIslandField.valueNoise(px, pz, scale, 51000 + sector * 97);
        double[] cum = d.extrasCumulative();
        for (int i = 0; i < cum.length; i++) {
            if (r < cum[i]) {
                return d.extras().get(i);
            }
        }
        return d.main() != null ? d.main() : fallback;
    }
}






