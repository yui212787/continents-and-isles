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
import java.util.HashSet;
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
 */
public class ContinentsAndIslesBiomeSource extends BiomeSource {

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

    private final BiomeSource delegate;
    private final int radius;
    private final int transition;
    private final int grid;
    private final double islandChance;
    /** 扇区配置：构造函数读取并缓存（getNoiseBiome 高频调用，避免每次 new） */
    private final ContinentIslandField.Config cfg;
    private final List<Holder<Biome>> islandPool;
    private final List<Holder<Biome>> mainlandPool;

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
        // 用配置值覆盖 JSON 参数（JSON 中的值仅为占位）
        this.radius = CAIConfig.RADIUS.get();
        this.transition = CAIConfig.TRANSITION.get();
        this.grid = CAIConfig.GRID.get();
        this.islandChance = CAIConfig.ISLAND_CHANCE.get();
        this.cfg = new ContinentIslandField.Config(
            this.radius, this.transition, this.grid, this.islandChance);
        this.islandPool = islandPool;
        this.mainlandPool = mainlandPool;
        // sectorBiomeData 延迟初始化：构造函数中调用 findBiome() 会触发 delegate.possibleBiomes()，
        // 而 fabric_biome_api_v1 的 mixin 会在注册表加载阶段就执行 MultiNoiseBiomeSource.parameters()，
        // 此时 multi_noise_biome_source_parameter_list/minecraft:overworld 尚未绑定 → 崩溃。
        // 改为在第一次 getNoiseBiome 时才初始化。
        // 加载外岛群系黑名单
        this.outerIslandBlacklist = new HashSet<>();
        for (String s : CAIConfig.OUTER_ISLAND_BIOME_BLACKLIST.get()) {
            if (s == null) continue;
            try {
                ResourceLocation loc = ResourceLocation.parse(s.trim());
                this.outerIslandBlacklist.add(loc);
            } catch (Exception ignored) {}
        }
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

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
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

        double dist = Math.sqrt(bx * bx + bz * bz);
        if (dist < this.radius) {
            ContinentIslandField.Config cfgIsl = this.cfg;
            // 群岛扇区：过渡带由 islandSectorFalloff（宽空间场）驱动，窗口 0.05 < falloff <= 0.40
            // 与 ContinentIslandField.bias 的过渡权重 extW 完全对齐 → 群系和地形 1:1 匹配。
            // 此窗口委托原版多噪声源，用实际气候参数（continents/erosion/offset）判定群系，
            // 陆地 → 沙滩 → 浅海 → 深海 自然渐变，没有断崖、没有草地夹沙海错位。
            double islExtHere = ContinentIslandField.islandSectorFalloff(bx, bz, cfgIsl);
            if (islExtHere > 0.05) {
                if (islExtHere <= 0.40) {
                    // ===== 过渡带（~80~150 格宽缓坡）：原版多噪声源全权处理群系 =====
                    Holder<Biome> dlg = this.delegate.getNoiseBiome(x, y, z, sampler);
                    // 过滤剔除群系（恶地/冰刺/风袭系不在超大陆生成）
                    if (this.isExcluded(dlg)) {
                        return this.pickMainlandBiome(x, y, z, sampler);
                    }
                    // 群岛扇区非雪原/山脉，禁止含雪群系
                    if (isSnowy(dlg) && !allowSnow(bx, bz)) {
                        return this.pickMainlandBiome(x, y, z, sampler);
                    }
                    // 保留过渡带河网：水域侧的河槽显示河流群系（原版可能判浅海，用户要大量小河）
                    if (ContinentIslandField.islandTransitionWeight(bx, bz, cfgIsl) > 0.02
                        && ContinentIslandField.islandTransitionRiver(bx, bz) > 0.5) {
                        double b = ContinentIslandField.bias(bx, bz, cfgIsl);
                        if (b < ContinentIslandField.LAND_BIAS_THRESHOLD) {
                            return this.mainlandPool.get(RIVER);
                        }
                    }
                    return dlg;
                }
                // 扇区内部（falloff > 0.40，对应 mask 也已进入内海区）：走群岛完整逻辑
                return this.pickMainlandBiome(x, y, z, sampler);
            }
            // 大陆核心：少量群系、每种占大片面积（大尺度气候噪声驱动，地形起伏由噪声路由保证）
            return this.pickMainlandBiome(x, y, z, sampler);
        }
        boolean land = ContinentIslandField.bias(bx, bz, cfg) >= ContinentIslandField.LAND_BIAS_THRESHOLD;
        if (dist >= this.radius + this.transition && land && !this.islandPool.isEmpty()) {
            // 外围岛屿：每个岛屿固定一个群系（pickIslandBiome 内部已处理黑名单，此处再兜底）
            Holder<Biome> b = this.pickIslandBiome(bx, bz, cfg);
            return isOuterIslandBlacklisted(b) ? outerIslandFallback() : b;
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

    /** 该位置是否允许雪群系：仅雪原扇区（扇区5）与山脉扇区（扇区0） */
    private boolean allowSnow(double px, double pz) {
        ContinentIslandField.Config cfg = this.cfg;
        double snowMask = ContinentIslandField.sectorMask(5, px, pz, cfg);
        double mountMask = ContinentIslandField.mountainValue(px, pz, this.radius);
        return snowMask > 0.35 || mountMask > 0.35;
    }

    /** 雪群系且不在允许区 → 替换为普通大陆群系 */
    private Holder<Biome> filterSnowBiome(Holder<Biome> biome, double px, double pz, int x, int y, int z, Climate.Sampler sampler) {
        if (isSnowy(biome) && !allowSnow(px, pz)) {
            return this.pickMainlandBiome(x, y, z, sampler);
        }
        return biome;
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

    /** 樱花树林群系索引（mainlandPool 中的 cherry_grove） */
    private static final int CHERRY_GROVE = 23;

    /** 冰封峰顶群系索引（mainlandPool 中的 frozen_peaks，雪山真实化） */
    private static final int FROZEN_PEAKS = 26;

    /** 沙滩群系索引（mainlandPool 中的 beach，群岛过渡带用） */
    private static final int BEACH = 27;

    /** 雪滩群系索引（mainlandPool 中的 snowy_beach，低温海岸用） */
    private static final int SNOWY_BEACH = 28;

    /** 恶地群系索引（mainlandPool 中的 badlands，沙漠扇区内生成） */
    private static final int BADLANDS = 29;

    /** 繁茂恶地群系索引（mainlandPool 中的 wooded_badlands，沙漠扇区内生成） */
    private static final int WOODED_BADLANDS = 30;

    /** 侵蚀恶地群系索引（mainlandPool 中的 eroded_badlands，陶瓦尖塔山） */
    private static final int ERODED_BADLANDS = 31;

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

    /**
     * 大陆核心群系：以平原/森林为主。
     * 固定扇区（每种出现一次）：山脉（山脚→山腰→峰顶分级）、沙漠、丛林、雪原、群岛（内海+小岛）、热带草原。
     * 扇区按泰拉瑞亚式对立排布，方位由世界种子随机旋转（对立关系不变）。
     * 扇区之间用平滑 mask + 过渡带噪声混合，天然渐变（大胆过渡）。
     * 小斑块（直径约 25~100 格，少量）：竹林、红树林、石岸。
     */
    private static final int[] SECTOR_BIOMES = { -1, 4, 6, 3, 7, 5 }; // 扇区 1..5 → 丛林/沼泽/沙漠/热带草原/雪原

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
        if (!needFix) return biome;
        SectorBiomeData sd = getSectorBiomeData()[sector];
        if (sd != null && sd.main() != null) return sd.main();
        return pool.get(FALLBACK_SECTOR_MAIN[sector]);
    }

    /**
     * 沙漠扇区：沙漠为主，混入小片恶地（约占扇区 20%~30%）。
     * 恶地以陶瓦山为主——侵蚀恶地（陶瓦尖塔山）点缀 + 恶地丘陵为主力，繁茂恶地少量。
     * 沙漠中再用 5~10 格的小尺度噪声点缀稀疏小树林（绿洲感）。
     */
    private Holder<Biome> desertSectorBiome(double px, double pz, List<Holder<Biome>> pool) {
        double bad = ContinentIslandField.valueNoise(px, pz, 300, 9001);
        if (bad > 0.74) { // 恶地约占 20%~30%
            double detail = ContinentIslandField.valueNoise(px, pz, 90, 9002);
            if (detail > 0.78) return pool.get(ERODED_BADLANDS);  // 陶瓦尖塔山
            if (detail > 0.32) return pool.get(BADLANDS);         // 陶瓦丘陵（主力）
            return pool.get(WOODED_BADLANDS);                     // 少量繁茂
        }
        // 沙漠小树林点缀：5~10 格小片，稀疏出现
        double grove = ContinentIslandField.valueNoise(px, pz, 10, 9003);
        if (grove > 0.88) {
            return pool.get(10); // forest 小树林
        }
        return pool.get(3); // desert
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
        if (ContinentIslandField.islandSectorIsLand(px, pz)) {
            return randomIslandBiome(px, pz);
        }

        // ===== 分支2：混合水陆判定 =====
        double finalBias = ContinentIslandField.bias(px, pz, cfg);
        boolean isLand = finalBias >= ContinentIslandField.LAND_BIAS_THRESHOLD;
        if (isLand) {
            // 陆地：正常陆地群系（平原/森林/沼泽等），沙滩不再强制，自主生成
            return baseMainlandBiome(px, pz, dist, angle, pool);
        }

        // ===== 分支3：水域 =====
        // 过渡带河网优先：外缘浅海的密细河道显示河流群系
        double trans = ContinentIslandField.islandTransitionWeight(px, pz, cfg);
        if (trans > 0.02 && ContinentIslandField.islandTransitionRiver(px, pz) > 0.5) {
            return pool.get(RIVER);
        }
        // 其余水域一律内海；沙滩由原版自主生成
        return pool.get(ISLAND_SECTOR_OCEAN);
    }

    /**
     * 山脉扇区（模拟真实山脉）：按与地形完全一致的结构值（mountainValue，即参数 mask）分层。
     * 结构值由蜿蜒 mask × 峰谷结构决定，与 MountainSector 的地形抬升数值完全相同——
     * 群系永远跟实际山高走，不会错位。
     * <ul>
     *   <li>峰顶（结构值高）：按温度真实分带——极寒=冰封峰顶、寒=积雪峰（jagged/snowy_slopes）、
     *       温=积雪斑驳（细节噪声决定这座峰有没有雪）、热=秃岩峰（原版热带高山那样）</li>
     *   <li>山腰：高寒雪坡 / 高山草甸 / 山坡针叶林（雪线只出现在低温段）</li>
     *   <li>山脚/山谷：草甸/针叶林/森林（谷地结构值低，自然回落低地群系）</li>
     * </ul>
     */
    private Holder<Biome> mountainRangeBiome(double mask, double px, double pz, List<Holder<Biome>> pool) {
        double temp = ContinentIslandField.valueNoise(px, pz, 400, 707);
        if (mask > 0.55) {
            // 峰顶：温度决定雪线高低，细节噪声让积雪斑驳（真实雪山：有的峰有雪、有的露岩）
            double snow = ContinentIslandField.valueNoise(px, pz, 64, 6006);
            if (temp < 0.30) return pool.get(FROZEN_PEAKS);                  // 极寒：冰封峰顶
            if (temp < 0.48) return pool.get(2);                              // 寒：雪线低，雪坡+积雪峰
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
            if (temp < 0.30) return pool.get(2);   // 高寒：雪坡
            if (temp < 0.66) return pool.get(13);  // 高山草甸
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
        // 边缘环山带（可选，默认关闭）：山峰系群系，与 RingMountain 环山带（0.97R~1.0R）对齐，
        // 从 0.95R 开始（略提前于地形抬升，保证过渡自然）
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
        double h = ContinentIslandField.islandSectorHash(px, pz, 12345);
        int idx = (int) (h * all.size());
        return all.get(Math.min(idx, all.size() - 1));
    }

    /** 群岛可用群系全池：delegate 的所有可能群系剔除海洋/河流/海滩/蘑菇岛类（蘑菇岛只保留强制生成的一个），
     *  并剔除含雪群系（群岛扇区非雪原/山脉，禁雪） */
    private List<Holder<Biome>> allLandBiomes() {
        if (this.allLandBiomesCache == null) {
            this.allLandBiomesCache = this.delegate.possibleBiomes().stream()
                .filter(h -> !isOceanOrBeach(h))
                .filter(h -> !isSnowy(h))
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

    /** 每个岛屿固定一个群系：按所在网格单元哈希，从岛群系池中确定性选取；
     *  若命中外岛黑名单，则向后扫描第一个非黑条目，再不行走兜底 */
    private Holder<Biome> pickIslandBiome(double bx, double bz, ContinentIslandField.Config cfg) {
        long cx = ContinentIslandField.cellX(bx, cfg);
        long cz = ContinentIslandField.cellZ(bz, cfg);
        double h = ContinentIslandField.hash(cx, cz, 707);
        int n = this.islandPool.size();
        if (n == 0) return outerIslandFallback();
        int start = (int) (h * n) % n;
        // 命中黑名单 → 最多扫一圈找一个非黑的
        if (!this.outerIslandBlacklist.isEmpty()) {
            int i = start;
            do {
                Holder<Biome> b = this.islandPool.get(i);
                if (!isOuterIslandBlacklisted(b)) return b;
                i = (i + 1) % n;
            } while (i != start);
            // 整个池都被黑了 → 走兜底
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

        // 雪原扇区（5）/丛林扇区（1）用更大噪声尺度 → 附属针叶林/冰刺/竹林/红树林等形成大片斑块，而非碎点
        int scale = (sector == 5 || sector == 1) ? 520 : 260;
        double r = ContinentIslandField.valueNoise(px, pz, scale, 51000 + sector * 97);
        double[] cum = d.extrasCumulative();
        for (int i = 0; i < cum.length; i++) {
            if (r < cum[i]) return d.extras().get(i);
        }
        return d.main() != null ? d.main() : fallback;
    }
}
