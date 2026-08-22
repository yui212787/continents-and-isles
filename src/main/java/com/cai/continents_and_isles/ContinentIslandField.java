package com.cai.continents_and_isles;

import net.minecraft.util.Mth;

/**
 * 大陆/岛屿的共享"场"逻辑：只依赖 (x, z) 与配置，输出一个大陆度偏置值。
 * 密度函数 {@link RadialLand} 用它驱动地形（effective = vanillaContinents + bias），
 * 群系源 {@link ContinentsAndIslesBiomeSource} 用它判断大陆/岛屿/海洋，二者保持一致。
 *
 * bias 值越大越偏陆地：
 * <ul>
 *   <li>大陆核心：保证陆地并保留起伏（聚合一片）</li>
 *   <li>大陆边缘：平滑的曲折海岸线</li>
 *   <li>外围：离散的单个圆形岛屿，散布在深海中</li>
 * </ul>
 */
public final class ContinentIslandField {

    /** 诊断日志（扇区方位、湿地带采样等） */
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ContinentIslandField.class);

    /** bias 达到该值时按陆地处理（群系层面与海岸线判定） */
    public static final double LAND_BIAS_THRESHOLD = -0.12;

    /** 必生成大湖：中心位置比例，由配置加载 */
    public static double lakeCenterFraction = 0.19;

    /** 湖泊数量：原位置湖 + 山峰扇区湖 + 随机扇区湖 */
    public static final int LAKE_COUNT = 3;
    /** 湖型（0=群系湖, 1=深湖, 2=岛湖），由种子决定 */
    public static final int[] lakeType = new int[LAKE_COUNT];
    /** 各湖中心坐标（格） */
    public static final double[] lakeCX = new double[LAKE_COUNT];
    public static final double[] lakeCZ = new double[LAKE_COUNT];
    /** 各湖实际半径（格），由种子决定 */
    public static final double[] lakeRadiusActual = new double[LAKE_COUNT];
    /** 群系湖选定的群系索引（映射到 mainlandPool），由种子决定 */
    public static final int[] lakeBiomeIndex = new int[LAKE_COUNT];
    /** 岛湖的岛屿稀疏参数（0=少而大, 1=多而小），由种子决定 */
    public static final double[] islandLakeParam = new double[LAKE_COUNT];
    /** 是否为高山天池（仅山峰扇区湖可能为 true），由种子决定 */
    public static final boolean[] lakeTianchi = new boolean[LAKE_COUNT];

    /** 群系湖候选群系中"沼泽"的索引（与 ContinentsAndIslesBiomeSource.LAKE_BIOME_MAP 索引一致） */
    public static final int LAKE_BIOME_SWAMP = 4;

    /** 大陆半径（格），由配置加载，供 DeepLake 等共享 */
    public static int continentRadius = 4250;
    /** 超大陆 → 海洋过渡带宽（格），默认 600；配置可覆盖 */
    public static int continentTransition = 160;
    /** 外围岛屿生成网格（格），默认 400；配置可覆盖 */
    public static int continentGrid = 400;
    /** 外围岛屿概率（每个网格单元），默认 0.22；配置可覆盖 */
    public static double continentIslandChance = 0.35;

    /** 固定扇区参数：半宽（度）、环带内/外径比例，由配置加载 */
    public static double sectorHalfWidthDeg = 20.0;
    /** 群岛扇区额外半宽（度）：仅在群岛扇区角度判定上追加，向两侧扩大内海/小岛/压低带/湿地带，其他扇区不变 */
    public static double islandSectorHalfExtraDeg = 5.0;
    public static double sectorDistLo = 0.30;
    public static double sectorDistHi = 0.85;

    /** 群岛扇区索引（原沼泽位置，扇区 2）：内海 + 小岛 */
    public static final int ISLAND_SECTOR = 2;

    /** 边缘环山开关（默认开启，与 CAIConfig 默认一致），由配置加载 */
    public static boolean ringMountainEnabled = true;

    /** 三个大湖是否启用（与 lakeType/lakeCX 等索引一一对应），由配置加载 */
    public static final boolean[] lakeEnabled = new boolean[LAKE_COUNT];

    /** 远海区起点（按超大陆半径倍数），超过此距离后岛屿生成使用单独倍率 */
    public static double farIslandStartMul = 2.5;
    /** 远海区岛屿生成概率倍率（0 则远海无岛，>1 则远海更多） */
    public static double farIslandMul = 1.35;

    /** 六大固定扇区的整体旋转角（弧度）：每个世界随机、同一世界内恒定，群系与地形共用 */
    private static volatile double sectorRotation;

    /** 世界种子混合值：initSectorRotation 时由世界种子派生，混入 hash → 所有噪声场/单元格哈希随世界种子变化。
     *  否则不同种子会生成完全相同的群系/岛屿/湖泊图案（只有扇区角度在转）。 */
    private static long worldSeedMix = 0L;

    /** 群岛扇区必生成的蘑菇岛单元格（由种子决定），坐标为 300 格单元网格索引 */
    public static long mushroomCellX;
    public static long mushroomCellZ;

    /** 海洋神殿保留区：中心 block 坐标（由种子在群岛锚点附近选定），半径内清空岛屿、强制深海 */
    public static final double MONUMENT_CLEAR_RADIUS = 300.0;
    private static double monumentCenterX;
    private static double monumentCenterZ;
    private static boolean monumentInitialized = false;
    /** 神殿生成的中心 chunk 坐标（structure_set spacing=1 保证该 chunk 必被判定） */
    public static int monumentChunkX;
    public static int monumentChunkZ;

    /** 沙漠扇区必定生成的沙漠神庙：中心 block/chunk 坐标（种子在沙漠扇区环带内选定） */
    private static double desertPyramidX;
    private static double desertPyramidZ;
    private static int desertPyramidChunkX;
    private static int desertPyramidChunkZ;
    private static boolean desertPyramidInitialized = false;

    /** 大陆上必定生成的林地府邸：中心 block/chunk 坐标（种子在扇区间隙选定，周围强制黑森林） */
    private static double mansionX;
    private static double mansionZ;
    private static int mansionChunkX;
    private static int mansionChunkZ;
    private static boolean mansionInitialized = false;

    /**
     * 由世界种子确定性派生扇区整体旋转角，使每个新世界的扇区方位都不同，
     * 但山峰↔沙漠、丛林↔热带草原、沼泽↔雪原的对立关系保持不变。
     */
    public static void initSectorRotation(long seed) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        // 先设置世界种子混合值：后续 initLake/initMushroomCell 等内部调用的 hash() 也随种子变化
        worldSeedMix = h;
        sectorRotation = (h & 0x7fffffffL) / (double) 0x7fffffffL * Math.PI * 2.0;
        initLake(seed);
        initMushroomCell(seed);
        initMonument(seed);
        initDesertPyramid(seed);
        initWoodlandMansion(seed);
        initWetlandSwampHut(seed);
        // 诊断日志：输出扇区方位，便于在游戏内核对群岛扇区（2）方向与湿地位置
        LOGGER.info("initSectorRotation: seed={}, sectorRotation={} rad ({}°), 群岛中心角={}°",
            seed,
            String.format(java.util.Locale.ROOT, "%.4f", sectorRotation),
            String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(sectorRotation)),
            String.format(java.util.Locale.ROOT, "%.1f", Math.toDegrees(sectorCenterAngle(ISLAND_SECTOR))));
    }

    /** 由世界种子在群岛扇区中心附近指定一个单元格为必生成蘑菇岛 */
    private static void initMushroomCell(long seed) {
        long h = seed ^ 0x55AA5A5A12345678L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        // 群岛扇区中心（~0.55R）附近取基准单元格，再种子偏移 ±1 格
        double center = sectorCenterAngle(ISLAND_SECTOR);
        double cx = Math.cos(center) * 0.55 * continentRadius;
        double cz = Math.sin(center) * 0.55 * continentRadius;
        long baseX = Mth.lfloor(cx / 300.0);
        long baseZ = Mth.lfloor(cz / 300.0);
        long offX = Math.round((hash(baseX, baseZ, 771) - 0.5) * 3.0);
        long offZ = Math.round((hash(baseX, baseZ, 772) - 0.5) * 3.0);
        mushroomCellX = baseX + offX;
        mushroomCellZ = baseZ + offZ;
    }

    /** 该位置是否属于必生成蘑菇岛的单元格 */
    public static boolean islandMushroomCell(double x, double z) {
        return Mth.lfloor(x / 300.0) == mushroomCellX && Mth.lfloor(z / 300.0) == mushroomCellZ;
    }

    /**
     * 由世界种子在群岛扇区锚点（0.55R 环带 + 扇区 2 方位）附近选定海洋神殿保留区中心：
     * <ul>
     *   <li>基准为锚点所在 300 格单元网格，种子偏移 ±1 单元</li>
     *   <li>避开必生成蘑菇岛单元格（避免保留区吞掉蘑菇岛）</li>
     *   <li>避开三个湖（保留区半径 + 湖半径 + 60 格余量），否则向远离湖方向推 1 单元</li>
     *   <li>校验保留区中心仍在群岛斑块 mask 高值区，否则回退锚点</li>
     * </ul>
     */
    private static void initMonument(long seed) {
        double R = continentRadius;
        double centerAng = sectorCenterAngle(ISLAND_SECTOR);
        double anchorX = Math.cos(centerAng) * 0.55 * R;
        double anchorZ = Math.sin(centerAng) * 0.55 * R;

        long h = seed ^ 0x1B873593D5A1B2C3L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;

        long baseX = Mth.lfloor(anchorX / 300.0);
        long baseZ = Mth.lfloor(anchorZ / 300.0);
        long offX = ((h >>> 10) & 0x7fffffffL) % 3 - 1; // -1..1 单元
        long offZ = ((h >>> 30) & 0x7fffffffL) % 3 - 1;
        long cellX = baseX + offX;
        long cellZ = baseZ + offZ;

        // 避开蘑菇岛单元格（2 单元内则反向推 2 单元）
        if (Math.abs(cellX - mushroomCellX) <= 1 && Math.abs(cellZ - mushroomCellZ) <= 1) {
            cellX = baseX + (offX <= 0 ? -2 : 2);
            cellZ = baseZ + (offZ <= 0 ? -2 : 2);
        }

        double cx = (cellX + 0.5) * 300.0;
        double cz = (cellZ + 0.5) * 300.0;
        // 避开三个湖
        for (int i = 0; i < LAKE_COUNT; i++) {
            double lx = lakeCenterX(i);
            double lz = lakeCenterZ(i);
            double dx = cx - lx;
            double dz = cz - lz;
            double need = lakeRadiusActual[i] + MONUMENT_CLEAR_RADIUS + 60.0;
            if (dx * dx + dz * dz < need * need) {
                double ang = Math.atan2(dz, dx);
                cellX = Mth.lfloor(cx / 300.0) + (Math.cos(ang) >= 0 ? 1 : -1);
                cellZ = Mth.lfloor(cz / 300.0) + (Math.sin(ang) >= 0 ? 1 : -1);
                cx = (cellX + 0.5) * 300.0;
                cz = (cellZ + 0.5) * 300.0;
            }
        }
        // 校验保留区中心仍在群岛斑块 mask 高值区（否则地形不会深海化），回退锚点单元
        if (islandSectorMask(cx, cz, continentRadius) < 0.25) {
            cellX = baseX;
            cellZ = baseZ;
            cx = (cellX + 0.5) * 300.0;
            cz = (cellZ + 0.5) * 300.0;
        }

        monumentCenterX = cx;
        monumentCenterZ = cz;
        monumentChunkX = (int) Mth.lfloor(cx / 16.0);
        monumentChunkZ = (int) Mth.lfloor(cz / 16.0);
        monumentInitialized = true;
    }

    /** 该位置是否在海洋神殿保留区内（半径内无岛屿、深海、deep ocean 群系） */
    public static boolean isInMonumentClear(double x, double z) {
        if (!monumentInitialized) {
            return false;
        }
        double dx = x - monumentCenterX;
        double dz = z - monumentCenterZ;
        return dx * dx + dz * dz < MONUMENT_CLEAR_RADIUS * MONUMENT_CLEAR_RADIUS;
    }

    /** 神殿生成的中心 chunk 位置（structure_set spacing=1 时必被判定），未初始化返回 null */
    public static net.minecraft.world.level.ChunkPos monumentChunkPos() {
        if (!monumentInitialized) {
            return null;
        }
        return new net.minecraft.world.level.ChunkPos(monumentChunkX, monumentChunkZ);
    }

    /** 群系湖（湖2）选定群系是否为沼泽（女巫小屋生成条件），湖被禁用时返回 false */
    public static boolean isSwampBiomeLake() {
        return lakeType[2] == 0 && lakeBiomeIndex[2] == LAKE_BIOME_SWAMP && lakeRadiusActual[2] > 0.0;
    }

    /** 群系湖中女巫小屋的生成位置（湖内随机位置所在 chunk，仅沼泽湖返回非 null，其余返回 null）。
     *  位置由种子哈希在湖内随机选取（随机角度 + 15%~85% 半径），不固定在湖心。 */
    public static net.minecraft.world.level.ChunkPos swampHutChunkPos() {
        if (!isSwampBiomeLake()) {
            return null;
        }
        double lx = lakeCenterX(2);
        double lz = lakeCenterZ(2);
        double lr = lakeRadiusActual[2];
        long hx = Mth.lfloor(lx);
        long hz = Mth.lfloor(lz);
        double ang = hash(hx, hz, 77701) * Math.PI * 2.0;
        double rr = 0.15 + hash(hx, hz, 77702) * 0.70;
        double wx = lx + Math.cos(ang) * rr * lr;
        double wz = lz + Math.sin(ang) * rr * lr;
        int cx = (int) Mth.lfloor(wx / 16.0);
        int cz = (int) Mth.lfloor(wz / 16.0);
        return new net.minecraft.world.level.ChunkPos(cx, cz);
    }

    /**
     * 由世界种子在沙漠扇区（扇区 3）环带内选定沙漠神庙位置：
     * 以扇区中心角为基准，种子哈希角度偏移 ±15°（半宽 20° 的全强度区）、半径 0.42R~0.72R，
     * 必定落在沙漠扇区 mask 高值区（群系为沙漠或恶地家族，满足 has_structure/desert_pyramid 标签）；
     * 若与群系湖（湖2）重叠则用第二个哈希重试，避免神庙掉进湖里。
     */
    private static void initDesertPyramid(long seed) {
        double R = continentRadius;
        double centerAng = sectorCenterAngle(3); // 沙漠扇区
        double px = 0, pz = 0;
        for (int attempt = 0; attempt < 4; attempt++) {
            double angOff = (hash(seed, attempt, 8811) - 0.5) * 2.0 * Math.toRadians(15.0);
            double rr = 0.42 + hash(seed, attempt + 50, 8812) * 0.30;
            double tx = Math.cos(centerAng + angOff) * rr * R;
            double tz = Math.sin(centerAng + angOff) * rr * R;
            // 避开三个湖（含 60 格缓冲）
            boolean nearLake = false;
            for (int i = 0; i < LAKE_COUNT; i++) {
                double dx = tx - lakeCenterX(i);
                double dz = tz - lakeCenterZ(i);
                double need = lakeRadiusActual[i] + 60.0;
                if (dx * dx + dz * dz < need * need) { nearLake = true; break; }
            }
            if (!nearLake) { px = tx; pz = tz; break; }
        }
        if (px == 0 && pz == 0) { // 兜底：扇区中心角 0.55R
            px = Math.cos(centerAng) * 0.55 * R;
            pz = Math.sin(centerAng) * 0.55 * R;
        }
        desertPyramidX = px;
        desertPyramidZ = pz;
        desertPyramidChunkX = (int) Mth.lfloor(px / 16.0);
        desertPyramidChunkZ = (int) Mth.lfloor(pz / 16.0);
        desertPyramidInitialized = true;
    }

    /** 沙漠神庙生成的中心 chunk 位置（structure_set spacing=1 时必被判定），未初始化返回 null */
    public static net.minecraft.world.level.ChunkPos desertPyramidChunkPos() {
        if (!desertPyramidInitialized) {
            return null;
        }
        return new net.minecraft.world.level.ChunkPos(desertPyramidChunkX, desertPyramidChunkZ);
    }

    /**
     * 由世界种子在扇区间隙（普通大陆）选定林地府邸位置：
     * 多次尝试找一个不在任何扇区核心（mask < 0.40）、不在湖内的点；
     * 确定后该位置周围 128×128 格由群系源强制为黑森林（府邸为 80×80 格建筑，需要黑森林环境）。
     */
    private static void initWoodlandMansion(long seed) {
        double R = continentRadius;
        double px = 0, pz = 0;
        boolean ok = false;
        for (int attempt = 0; attempt < 16 && !ok; attempt++) {
            double ang = hash(seed, attempt, 8821) * Math.PI * 2.0;
            double rr = 0.32 + hash(seed, attempt + 100, 8822) * 0.40; // 0.32R~0.72R
            double tx = Math.cos(ang) * rr * R;
            double tz = Math.sin(ang) * rr * R;
            // 避开所有扇区核心：任一扇区 mask >= 0.40 即弃（保留在山脉/群岛/雪原等扇区内会破坏其群系）
            double best = 0.0;
            for (int s = 0; s < 6; s++) {
                best = Math.max(best, sectorMask(s, tx, tz, R));
            }
            if (best >= 0.40) continue;
            // 避开三个湖（含 80 格缓冲）
            boolean nearLake = false;
            for (int i = 0; i < LAKE_COUNT; i++) {
                double dx = tx - lakeCenterX(i);
                double dz = tz - lakeCenterZ(i);
                double need = lakeRadiusActual[i] + 80.0;
                if (dx * dx + dz * dz < need * need) { nearLake = true; break; }
            }
            if (nearLake) continue;
            px = tx; pz = tz; ok = true;
        }
        if (!ok) { // 兜底：固定角度 37°、0.50R
            px = Math.cos(37.0 * Math.PI / 180.0) * 0.50 * R;
            pz = Math.sin(37.0 * Math.PI / 180.0) * 0.50 * R;
        }
        mansionX = px;
        mansionZ = pz;
        mansionChunkX = (int) Mth.lfloor(px / 16.0);
        mansionChunkZ = (int) Mth.lfloor(pz / 16.0);
        mansionInitialized = true;
    }

    /** 林地府邸生成的中心 chunk 位置（structure_set spacing=1 时必被判定），未初始化返回 null */
    public static net.minecraft.world.level.ChunkPos woodlandMansionChunkPos() {
        if (!mansionInitialized) {
            return null;
        }
        return new net.minecraft.world.level.ChunkPos(mansionChunkX, mansionChunkZ);
    }

    /** 林地府邸中心 block 坐标（群系源强制黑森林用），未初始化返回 0 */
    public static double mansionCenterX() {
        return mansionX;
    }

    /** 林地府邸中心 block 坐标（群系源强制黑森林用），未初始化返回 0 */
    public static double mansionCenterZ() {
        return mansionZ;
    }

    /** 湿地带保底女巫小屋的中心 chunk 坐标（由种子在湿地带内选定） */
    private static int wetlandSwampHutChunkX;
    private static int wetlandSwampHutChunkZ;
    private static boolean wetlandSwampHutInitialized = false;

    /**
     * 由世界种子在群岛-环山带过渡湿地带（0.80R~0.98R × 群岛扇区角度）内选定一个保底女巫小屋位置：
     * 以群岛扇区中心角为基准，种子哈希角度偏移 ±15°、半径 0.82R~0.96R，
     * 并校验该点 {@link #archipelagoWetlandBand} ≥ 0.5（湿地带主体区，群系必为沼泽/红树林，
     * 满足 has_structure/swamp_hut 标签）；校验失败则重试，最多 8 次后回退湿地带中心（0.89R 中心角）。
     * <p>
     * 这是"保底至少一个"的定点结构：除它之外，原版 swamp_huts 结构集仍可在其他沼泽群系
     * 中按原版规则继续生成，不限制数量。
     */
    private static void initWetlandSwampHut(long seed) {
        double R = continentRadius;
        double centerAng = sectorCenterAngle(ISLAND_SECTOR);
        double px = 0, pz = 0;
        boolean ok = false;
        for (int attempt = 0; attempt < 8 && !ok; attempt++) {
            double angOff = (hash(seed, attempt, 8831) - 0.5) * 2.0 * Math.toRadians(15.0);
            double rr = 0.82 + hash(seed, attempt + 50, 8832) * 0.14;
            double tx = Math.cos(centerAng + angOff) * rr * R;
            double tz = Math.sin(centerAng + angOff) * rr * R;
            // 必须落在湿地带主体区（band ≥ 0.5 → 群系 100% 为沼泽/红树林）
            if (archipelagoWetlandBand(tx, tz, R) >= 0.5) {
                px = tx;
                pz = tz;
                ok = true;
            }
        }
        if (!ok) { // 兜底：湿地带中心（群岛扇区中心角 0.89R）
            px = Math.cos(centerAng) * 0.89 * R;
            pz = Math.sin(centerAng) * 0.89 * R;
        }
        wetlandSwampHutChunkX = (int) Mth.lfloor(px / 16.0);
        wetlandSwampHutChunkZ = (int) Mth.lfloor(pz / 16.0);
        wetlandSwampHutInitialized = true;
    }

    /** 湿地带保底女巫小屋的中心 chunk 位置（structure_set spacing=1 时必被判定），未初始化返回 null */
    public static net.minecraft.world.level.ChunkPos wetlandSwampHutChunkPos() {
        if (!wetlandSwampHutInitialized) {
            return null;
        }
        return new net.minecraft.world.level.ChunkPos(wetlandSwampHutChunkX, wetlandSwampHutChunkZ);
    }

    /**
     * 由世界种子确定性决定三个湖：原位置湖、山峰扇区湖（可能为天池）、随机扇区湖。
     * 三种湖型**固定分配**（不再随机排列）：
     * <ul>
     *   <li>湖0（东湖）→ 岛湖：水面委托原版海洋群系 + 湖中散布原版小岛，半径 180~260</li>
     *   <li>湖1（山峰扇区湖）→ 深湖：纯深水面，由 DeepLake 雕刻碗形湖底（先缓后陡，最深处约 Y-17），
     *       湖盆由 LakeBasin 抬到海平面附近，约一半概率为天池（环形山），半径 120~200</li>
     *   <li>湖2（随机扇区湖）→ 群系湖：湖面固定 10 选 1 群系（沼泽/红树林/丛林/竹林/蘑菇岛/樱花树林/各海洋），
     *       由种子决定，半径 120~260</li>
     * </ul>
     * 固定分配的原因：群系湖若落在东湖/山湖，湖面群系随机可能选到冰冻海洋等"不像湖"的群系；
     * 东湖固定岛湖、山湖固定深湖，视觉更稳定；群系湖放在随机扇区，起到"惊喜环境"的点缀作用。
     */
    private static void initLake(long seed) {
        long h = seed ^ 0x6C62272E07BB0142L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;

        // 湖型固定分配：湖0（东湖）=岛湖、湖1（山峰扇区湖）=深湖、湖2（随机扇区湖）=群系湖
        int[] types = { 2, 1, 0 };

        // 随机扇区湖的候选扇区（避开山峰 0、群岛 2）：丛林 1 / 沙漠 3 / 热草 4 / 雪原 5
        int[] candidateSectors = { 1, 3, 4, 5 };
        int sectorPick = (int) (((h >>> 40) & 0x7fffffffL) % 4);
        int lake2Sector = candidateSectors[sectorPick];

        for (int i = 0; i < LAKE_COUNT; i++) {
            lakeType[i] = types[i];
            // 位置
            if (i == 0) {
                // 原位置湖：大陆核心内、出生点以东（lakeCenterX/Z 对 idx 0 用 lakeCenterFraction 计算）
                lakeCX[i] = lakeCenterFraction;
                lakeCZ[i] = 0.0;
            } else if (i == 1) {
                // 山峰扇区湖：扇区中心角 + 偏内半径 0.40R
                double ang = sectorCenterAngle(0);
                double rr = 0.40;
                lakeCX[i] = rr;
                lakeCZ[i] = ang;
            } else {
                // 随机扇区湖：所选扇区中心角 + 环带中段 0.55R
                double ang = sectorCenterAngle(lake2Sector);
                double rr = 0.55;
                lakeCX[i] = rr;
                lakeCZ[i] = ang;
            }
            // 半径按类型随机
            double r = ((h >>> (5 + i * 13)) & 0x7fffffffL) / (double) 0x7fffffffL;
            if (lakeType[i] == 1) {
                lakeRadiusActual[i] = 120.0 + r * 80.0;
            } else if (lakeType[i] == 2) {
                lakeRadiusActual[i] = 180.0 + r * 80.0;
            } else {
                lakeRadiusActual[i] = 120.0 + r * 140.0;
            }
            lakeBiomeIndex[i] = (int) (((h >>> (9 + i * 17)) & 0x7fffffffL) % LAKE_BIOME_POOL_SIZE);
            islandLakeParam[i] = ((h >>> (21 + i * 23)) & 0x7fffffffL) / (double) 0x7fffffffL;
            lakeTianchi[i] = false;
            // 配置开关：禁用的湖半径归零，后续 lakeValueAt/lakeCenter* 会自然跳过
            if (!lakeEnabled[i]) {
                lakeRadiusActual[i] = 0.0;
            }
        }
        // 山峰扇区湖：约一半概率为天池（周围环形山抬升）
        lakeTianchi[1] = ((h >>> 50) & 1L) == 1L;
    }

    /** 群系湖的湖面群系候选数量 */
    public static final int LAKE_BIOME_POOL_SIZE = 10; // 8 原版 + 蘑菇岛 + 樱花树林

    /**
     * 用当前大陆半径把湖心从"比例/角度"解析为实际坐标。
     * lakeCX[i] 存 0..1 的比例（湖0）或（cos 分量），lakeCZ[i] 存角度（湖1/湖2）——见 initLake。
     * 实际坐标在需要时通过 {@link #lakeCenterX(int)} / {@link #lakeCenterZ(int)} 计算。
     */
    public static double lakeCenterX(int idx) {
        double R = continentRadius;
        if (idx == 0) {
            return lakeCenterFraction * R;
        }
        double ang = lakeCZ[idx];
        double rr = lakeCX[idx];
        return Math.cos(ang) * rr * R;
    }

    public static double lakeCenterZ(int idx) {
        double R = continentRadius;
        if (idx == 0) {
            return 0.0;
        }
        double ang = lakeCZ[idx];
        double rr = lakeCX[idx];
        return Math.sin(ang) * rr * R;
    }

    public static double sectorRotation() {
        return sectorRotation;
    }

    /** 【双保险】在每个密度函数 compute() / 群系 getNoiseBiome() 首次调用前尝试同步配置。
     *  <p>ServerAboutToStart.loadConfig() 理论上更早触发，但如果 Mod 加载链路、
     *  客户端创建世界时序有差异，至少在第一次坐标计算时兜底读一遍 CAIConfig。
     *  配置文件此时必然已加载（能 compute 就意味着世界已存在），所以直接 .get() 安全。 */
    public static void ensureConfigLoaded() {
        try {
            int r = CAIConfig.RADIUS.get();
            if (r > 0 && r != continentRadius) {
                continentRadius = r;
                continentTransition = CAIConfig.TRANSITION.get();
                continentGrid = CAIConfig.GRID.get();
                continentIslandChance = CAIConfig.ISLAND_CHANCE.get();
                lakeCenterFraction = CAIConfig.LAKE_CENTER_FRACTION.get();
                sectorHalfWidthDeg = CAIConfig.SECTOR_HALF_WIDTH.get();
                islandSectorHalfExtraDeg = CAIConfig.ISLAND_SECTOR_EXTRA_DEG.get();
                sectorDistLo = CAIConfig.SECTOR_DIST_LO.get();
                sectorDistHi = CAIConfig.SECTOR_DIST_HI.get();
                ringMountainEnabled = CAIConfig.RING_MOUNTAIN_ENABLED.get();
                lakeEnabled[0] = Boolean.TRUE.equals(CAIConfig.LAKE_0_ENABLED.get());
                lakeEnabled[1] = Boolean.TRUE.equals(CAIConfig.LAKE_1_ENABLED.get());
                lakeEnabled[2] = Boolean.TRUE.equals(CAIConfig.LAKE_2_ENABLED.get());
                farIslandStartMul = CAIConfig.FAR_ISLAND_START_MULTIPLIER.get();
                farIslandMul = CAIConfig.FAR_ISLAND_CHANCE_MULTIPLIER.get();
            }
        } catch (Exception ignored) {
            // 极端情况（注册表测试、单元测试环境）：CAIConfig 未初始化则保留硬编码默认，不崩溃。
        }
    }
    /** 从 NeoForge 配置刷新大湖与扇区参数（在世界生成开始前调用） */
    public static void loadConfig() {
        continentRadius = CAIConfig.RADIUS.get();
        continentTransition = CAIConfig.TRANSITION.get();
        continentGrid = CAIConfig.GRID.get();
        continentIslandChance = CAIConfig.ISLAND_CHANCE.get();
        lakeCenterFraction = CAIConfig.LAKE_CENTER_FRACTION.get();
        sectorHalfWidthDeg = CAIConfig.SECTOR_HALF_WIDTH.get();
        islandSectorHalfExtraDeg = CAIConfig.ISLAND_SECTOR_EXTRA_DEG.get();
        sectorDistLo = CAIConfig.SECTOR_DIST_LO.get();
        sectorDistHi = CAIConfig.SECTOR_DIST_HI.get();
        ringMountainEnabled = CAIConfig.RING_MOUNTAIN_ENABLED.get();
        // 三个大湖独立开关
        lakeEnabled[0] = Boolean.TRUE.equals(CAIConfig.LAKE_0_ENABLED.get());
        lakeEnabled[1] = Boolean.TRUE.equals(CAIConfig.LAKE_1_ENABLED.get());
        lakeEnabled[2] = Boolean.TRUE.equals(CAIConfig.LAKE_2_ENABLED.get());
        // 远海区岛屿生成参数
        farIslandStartMul = CAIConfig.FAR_ISLAND_START_MULTIPLIER.get();
        farIslandMul = CAIConfig.FAR_ISLAND_CHANCE_MULTIPLIER.get();
    }

    public record Config(int radius, int transition, int grid, double islandChance) {}

    private ContinentIslandField() {
    }

    public static double bias(double x, double z, Config cfg) {
        double dist = Math.sqrt(x * x + z * z);
        if (dist < cfg.radius()) {
            // 大陆核心基盘：大陆度落在原版 spline 的合理陆地区间（0.4~0.8），
            // 过高（>0.9）会让 offset/factor spline 外推、地形异常耸高。
            double base = 0.6 + 0.14 * valueNoise(x, z, 600, 101);
            // 必生成大湖优先：湖面为水、湖中有小岛，岸线平滑过渡到陆地。
            // 必须先于群岛扇区判定——否则群岛扇区（角度随种子旋转）可能盖住湖0（固定 0° 方位），
            // 导致湖地形被群岛值覆盖、湖干涸。
            for (int i = 0; i < LAKE_COUNT; i++) {
                if (lakeRadiusActual[i] <= 0.0) {
                    continue; // 被配置禁用的湖跳过
                }
                double lcx = lakeCenterX(i);
                double lcz = lakeCenterZ(i);
                double lr = lakeRadiusActual[i];
                double dxn = (x - lcx) / lr;
                double dzn = (z - lcz) / lr;
                double dd = Math.sqrt(dxn * dxn + dzn * dzn);
                if (dd < 1.05) {
                    // 湖盆向岸边渐浅，岸线在 0.60~1.05 半径之间 S 形过渡到陆地——
                    // 过渡到 1.05 才结束（旧版在 1.0 硬切回大陆基盘，bias 突变导致岸边断崖/悬空）
                    double v = lakeValueAt(i, x, z, cfg);
                    double shallow = (Double.isNaN(v) ? -0.35 : v) + 0.12 * Mth.clamp(dd, 0.0, 1.0);
                    double edge = Mth.smoothstep((float) Mth.clamp((dd - 0.60) / 0.45, 0.0, 1.0));
                    return Mth.lerp(edge, shallow, base);
                }
            }
            // 群岛扇区（原沼泽位置）：内海 + 小岛，与周围陆地原版式自然海岸过渡。
            // 关键设计：全程由 islandSectorFalloff（非阈值化宽空间场）单变量驱动连续过渡，
            // 完全移除 mask 边界判断 → 彻底消除"falloff在中段但mask已过阈值"导致的 bias 跳变（断崖）。
            //   falloff<=0.05：纯陆地（大陆基盘 base≈0.6）
            //   falloff=0.05~0.40：过渡带，base → -0.65 平滑变化（空间≈80~150格宽）
            //   falloff>0.40：群岛主体，按 mask 决定内海/小岛值
            // 沙滩完全交给原版多噪声群系源自主生成，这里只做地形连续不制造台阶。
            // 两侧海岸：完全不做人工干预，由 angMask 的角度硬截止（20°外为0）+ 原版大陆基盘
            // 自然形成标准 MC 海岸线，不添加人工悬崖、人工礁石，也不做侧向下沉。
            double islMask = islandSectorMask(x, z, cfg);
            double islExt = islandSectorFalloff(x, z, cfg);
            if (islExt > 0.05) {
                // 过渡期权重：falloff 0.05 → 0（陆地），falloff 0.40 → 1（海洋）
                double extW = Mth.smoothstep((float) Mth.clamp((islExt - 0.05) / 0.35, 0.0, 1.0));
                if (islExt <= 0.40) {
                    // ===== 过渡带：纯 falloff 线性过渡，无侵蚀/河网，直接 base → -0.65 =====
                    return Mth.lerp(extW, base, -0.65);
                }
                // 群岛主体（falloff > 0.40）：内海 + 小岛
                // 内海固定 -0.65；mask 0.45+ 才向岛屿值回升（岸边直接入海，无突兀）
                double islVal = islandSectorValue(x, z);
                // 离岸缓冲环：islExt <= 0.20 时不允许小岛回升（w2=0），
                // 保证岛与沙滩（末端0.10）之间至少有约60格纯净海面缓冲，不相融不贴岸
                double w2 = (islExt > 0.20)
                    ? Mth.smoothstep((float) Mth.clamp((islMask - 0.45) / 0.40, 0.0, 1.0))
                    : 0.0;
                return (w2 > 0.0) ? Mth.lerp(w2, -0.65, islVal) : -0.65;
            }
            return base;
        }
        if (dist < cfg.radius() + cfg.transition()) {
            // 大陆边缘：海岸线略有曲折但整体聚合
            double t = (dist - cfg.radius()) / cfg.transition();
            double coast = valueNoise(x, z, 1100, 202);
            double fall = Mth.clamp(t * (1.15 - 0.30 * coast), 0.0, 1.0);
            return 0.6 * (1.0 - fall) - 0.60 * fall;
        }
        // 外围：每个网格单元至多一个岛屿，其余为深海
        // 距离 >= R * farIslandStartMul 时进入"远海区"，岛屿概率乘以 farIslandMul
        // 远海岛屿半径也略按倍率缩放（倍率 >1 时岛也略大，<1 时略小或消失）
        double baseChance = cfg.islandChance();
        double radiusScale = 1.0;
        if (dist >= cfg.radius() * farIslandStartMul) {
            baseChance *= farIslandMul;
            radiusScale = 0.85 + 0.40 * farIslandMul; // 0.85x（远海为 0 时不使用） ~ 2.05x
        }
        double effectiveChance = Mth.clamp(baseChance, 0.0, 1.0);
        long cx0 = Mth.lfloor(x / cfg.grid());
        long cz0 = Mth.lfloor(z / cfg.grid());
        // 遍历 3×3 邻域格子取最强岛值：岛中心可偏移 ±0.60 格漂进相邻格，
        // 且远海岛半径可超 1 格——若只检查自身格子，漂进相邻格的部分会被"切除"成方块状缺口。
        // 岛中心最多落在检查点所在格的 ±1 格范围内，3×3 邻域必然覆盖。
        double best = -0.90; // 深海基线
        if (effectiveChance > 0.0) {
            for (long dxx = -1; dxx <= 1; dxx++) {
                for (long dzz = -1; dzz <= 1; dzz++) {
                    long ccx = cx0 + dxx;
                    long ccz = cz0 + dzz;
                    if (hash(ccx, ccz, 303) >= effectiveChance) {
                        continue; // 该格不生成岛
                    }
                    // 岛中心偏移 ±0.60 格（≈±240 格）→ 可漂进相邻格子，打破规整方格感
                    double ox = (ccx + 0.5 + (hash(ccx, ccz, 404) - 0.5) * 1.2) * cfg.grid();
                    double oz = (ccz + 0.5 + (hash(ccx, ccz, 505) - 0.5) * 1.2) * cfg.grid();
                    double r = cfg.grid() * (0.38 + 0.18 * hash(ccx, ccz, 606)) * radiusScale;
                    // 域扭曲：让岛屿边缘不规则（避免标准圆形）
                    double warp = r * 0.35;
                    double wx = x + (valueNoise(x, z, 90, 1001) - 0.5) * 2.0 * warp;
                    double wz = z + (valueNoise(x, z, 90, 1002) - 0.5) * 2.0 * warp;
                    double ddx = wx - ox;
                    double ddz = wz - oz;
                    double rr = ddx * ddx + ddz * ddz;
                    if (rr < r * r) { // 快速排除（d<1），避免 sqrt
                        double d = Math.sqrt(rr) / r;
                        double e = 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
                        double v = -0.90 + 1.50 * e;
                        if (v > best) {
                            best = v;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 该位置所属的岛屿网格单元坐标 */
    public static long cellX(double x, Config cfg) {
        return Mth.lfloor(x / cfg.grid());
    }

    public static long cellZ(double z, Config cfg) {
        return Mth.lfloor(z / cfg.grid());
    }

    /**
     * 遍历三个湖，返回命中的湖在该位置的大陆度（负值=湖面，正值=湖中岛屿），
     * 不在任何湖区内返回 NaN。
     * 三种湖型：群系湖（浅水+少量小岛）、深湖（浅水，深度由 DeepLake 密度函数雕刻）、岛湖（大量岛屿）。
     */
    public static double lakeValue(double x, double z, Config cfg) {
        double best = Double.NaN;
        for (int i = 0; i < LAKE_COUNT; i++) {
            double v = lakeValueAt(i, x, z, cfg);
            if (!Double.isNaN(v)) {
                // 理论三个湖不重叠；若同时命中，取绝对值更大的（更接近水面核心或岛屿中心）
                best = Double.isNaN(best) ? v : (Math.abs(v) >= Math.abs(best) ? v : best);
            }
        }
        return best;
    }

    public static double lakeValueAt(int idx, double x, double z, Config cfg) {
        double lcx = lakeCenterX(idx);
        double lcz = lakeCenterZ(idx);
        double lr = lakeRadiusActual[idx];
        double dx = (x - lcx) / lr;
        double dz = (z - lcz) / lr;
        double dd2 = dx * dx + dz * dz;
        if (dd2 >= 1.0) {
            return Double.NaN;
        }
        double dd = Math.sqrt(dd2);

        if (lakeType[idx] == 2) {
            // 岛湖：岛屿数量不定（越少越大越多越小），集中在湖心，不贴岸
            double cell = lr * (0.32 - 0.08 * islandLakeParam[idx]);
            double prob = 0.25 + 0.30 * islandLakeParam[idx];
            double isleR = cell * (0.55 - 0.25 * islandLakeParam[idx]);
            long cx = Mth.lfloor(x / cell);
            long cz = Mth.lfloor(z / cell);
            if (hash(cx, cz, 909) < prob) {
                double ox = (cx + 0.5 + (hash(cx, cz, 404) - 0.5) * 0.4) * cell;
                double oz = (cz + 0.5 + (hash(cx, cz, 505) - 0.5) * 0.4) * cell;
                double ocx = (ox - lcx) / lr;
                double ocz = (oz - lcz) / lr;
                double odd = Math.sqrt(ocx * ocx + ocz * ocz);
                if (odd < 0.65) {
                    double d = Math.sqrt((x - ox) * (x - ox) + (z - oz) * (z - oz)) / isleR;
                    double e = 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
                    return -0.30 + 0.55 * e;
                }
            }
            return -0.35;
        }

        if (lakeType[idx] == 1) {
            // 深湖：纯水面、无小岛（地形完全由 DeepLake 密度函数雕刻，小岛会被挖空）。
            // 湖面大陆度取 -0.9（深海级）：深湖在山脉扇区，vanillaContinents 偏高（~0.8+），
            // 若湖心 bias 只到 -0.35，continents≈0.65 → offset 为正 → 湖心地面高出水面，
            // DeepLake 挖空 y<=63 会把水面以上的湖心地形留成"浮空岛"。
            return -0.9;
        }

        // 群系湖：极少量小岛（平均 0~1 个）。湖本身是圆形湖面，
        // 小岛改用"极坐标散点"生成（不再用方形格网）：
        // 以湖心为原点，由种子确定性决定 0~2 个候选岛（角度/半径/大小），每个岛是平滑圆斑。
        // 彻底消除格网直线边界（不再有"十字分割/方形斑块/域扭曲漂移"），湖面保持天然圆形；
        // 岛外一律回落水域值 -0.35（保持湖面全域水域，压低依赖基础 offset 自然形成深水）
        long hcx = Mth.lfloor(lcx);
        long hcz = Mth.lfloor(lcz);
        int isleCount = hash(hcx, hcz, 909) < 0.18 ? 1 + (hash(hcx, hcz, 910) < 0.35 ? 1 : 0) : 0;
        for (int k = 0; k < isleCount; k++) {
            double ang = hash(hcx, hcz, 911 + k) * Math.PI * 2.0;
            double rr = 0.10 + hash(hcx, hcz, 921 + k) * 0.55;          // 岛心半径 0.10~0.65 lr，不贴岸
            double ox = lcx + Math.cos(ang) * rr * lr;
            double oz = lcz + Math.sin(ang) * rr * lr;
            double isleR = lr * (0.12 + hash(hcx, hcz, 931 + k) * 0.10); // 岛半径 0.12~0.22 lr
            double d = Math.sqrt((x - ox) * (x - ox) + (z - oz) * (z - oz)) / isleR;
            if (d < 1.0) {
                double e = 1.0 - Mth.smoothstep((float) d);
                return -0.30 + 0.55 * e;
            }
        }
        return -0.35;
    }

    /**
     * 指定深湖的湖底 Y 坐标（仅 lakeType[idx]==1 时有效）。
     * 平滑碗形：湖岸边缘约 1.5 格深（Y61.5）→ 湖心 20 格深（Y43），越接近中心越深，全程平滑渐变。
     * 加入少量噪声使湖底不平整。
     * <p>
     * 雕刻范围覆盖 dd &lt; 0.95（几乎整个湖），湖岸最外圈（0.95~1.0）交给 bias + LakeBasin
     * 自然衔接——旧版只在 dd &lt; 0.65 雕刻，湖岸环带（0.65~1.0）跌回深海底，形成"岸边 10 格深沟"。
     */
    public static double deepLakeBottomY(int idx, double x, double z) {
        double lcx = lakeCenterX(idx);
        double lcz = lakeCenterZ(idx);
        double lr = lakeRadiusActual[idx];
        double dx = (x - lcx) / lr;
        double dz = (z - lcz) / lr;
        double dd2 = dx * dx + dz * dz;
        if (dd2 >= 1.0) return Double.NaN; // 快速排除，避免 sqrt
        double dd = Math.sqrt(dd2);
        // 湖岸最外圈不雕刻，交给 bias + LakeBasin 自然形成浅滩/岸线
        if (dd >= 0.95) return Double.NaN;

        // 深度噪声：±1 格起伏（保持碗形平滑）
        double noise = (valueNoise(x, z, 80, 2001) - 0.5) * 2.0;

        // 碗形：dd=0.95（湖岸）→ 1.5 格深（Y61.5）；dd=0（中心）→ 20 格深（Y43）
        double s = Mth.smoothstep((float) Mth.clamp(dd / 0.95, 0.0, 1.0));
        return 43.0 + 18.5 * s + noise;
    }

    /**
     * 扇区角度位置重排：位置 0..5（每 60°）依次摆放的扇区索引。
     * 新的对立关系（两两相差 180°）：
     * <ul>
     *   <li>位置0 山脉(0) ↔ 位置3 群岛(2)：高山对海洋</li>
     *   <li>位置1 沙漠(3) ↔ 位置4 丛林(1)：沙漠对雨林</li>
     *   <li>位置2 雪原(5) ↔ 位置5 热带草原(4)：雪原对热带草原</li>
     * </ul>
     * 扇区索引的语义不变（0=山脉、1=丛林、2=群岛、3=沙漠、4=热带草原、5=雪原），
     * 所有引用扇区索引的代码（地形/群系/湖）自动跟随新方位。
     */
    private static final int[] SECTOR_ANGLE_ORDER = { 0, 3, 5, 2, 1, 4 };

    /** 扇区中心角（弧度，含世界种子旋转）：按 {@link #SECTOR_ANGLE_ORDER} 映射到角度位置 */
    public static double sectorCenterAngle(int sector) {
        int pos = 0;
        for (int i = 0; i < SECTOR_ANGLE_ORDER.length; i++) {
            if (SECTOR_ANGLE_ORDER[i] == sector) {
                pos = i;
                break;
            }
        }
        return (-5 + 2 * pos) * Math.PI / 6.0 + sectorRotation();
    }

    /**
     * 通用扇区 mask（0~1）：角度 + 径向锥形，扇区中心最强、向边缘平滑衰减。
     * 角度域先做噪声扭曲（±11° 蜿蜒，380 格尺度），让扇区边界像真实自然区界一样弯曲，
     * 而不是笔直的放射状直线，消除刀切感的"切面"。
     * 过渡带比硬边界宽很多（角度全强度区为 75% 半宽、衰减到 135% 半宽；径向两侧各 0.10R/0.08R），
     * 使扇区之间、扇区与普通大陆之间都是平滑渐变而非硬切（大胆过渡）。
     * 群岛扇区（{@link #ISLAND_SECTOR}）额外乘中心加权，使群岛集中在环带中部（~0.55R），
     * 避免内海延伸到大陆核心/出生点（0.19R）附近。
     */
    public static double sectorMask(int sector, double x, double z, Config cfg) {
        double angle = Math.atan2(z, x);
        double center = sectorCenterAngle(sector);
        // 角度域扭曲：让扇区边界蜿蜒弯曲，消除笔直放射状的刀切切面
        // 双频噪声叠加：大尺度决定整体弯曲，小尺度增加边缘锯齿感
        double warpBig = (valueNoise(x, z, 380, 5100 + sector * 17) - 0.5) * 2.0 * 0.17; // ±9.7° 大蜿蜒
        double warpSmall = (valueNoise(x, z, 110, 5200 + sector * 13) - 0.5) * 2.0 * 0.05; // ±2.9° 小锯齿
        double warp = warpBig + warpSmall;
        double angOff = angle + warp - center;
        double delta = Math.abs(Math.atan2(Math.sin(angOff), Math.cos(angOff)));
        double half = Math.toRadians(sectorHalfWidthDeg);
        double inner = half * 0.75;
        double outer = half * 1.55; // 加宽角度过渡到 155% 半宽，相邻扇区自然交叠柔化
        double angMask = 1.0 - Mth.smoothstep((float) Mth.clamp((delta - inner) / (outer - inner), 0.0, 1.0));
        if (angMask <= 0.0) {
            return 0.0;
        }

        double R = cfg.radius();
        double lo = R * sectorDistLo;
        double hi = R * sectorDistHi;
        double loFall = R * Math.max(0.0, sectorDistLo - 0.14);
        double hiFall = R * Math.min(1.0, sectorDistHi + 0.14);
        double dist = Math.sqrt(x * x + z * z);
        double radMask;
        if (dist <= loFall || dist >= hiFall) {
            radMask = 0.0;
        } else if (dist < lo) {
            radMask = Mth.smoothstep((float) ((dist - loFall) / (lo - loFall)));
        } else if (dist > hi) {
            radMask = 1.0 - Mth.smoothstep((float) ((dist - hi) / (hiFall - hi)));
        } else {
            radMask = 1.0;
        }
        double mask = angMask * radMask;
        if (sector == ISLAND_SECTOR) {
            // 群岛集中在环带中部：rn=0（内缘）/1（外缘）→ 权重 0，rn=0.5（环带中心）→ 权重 1
            double rn = Mth.clamp((dist - lo) / (hi - lo), 0.0, 1.0);
            mask *= Mth.smoothstep((float) (rn * (1.0 - rn) * 4.0));
        }
        return Math.max(0.0, mask);
    }

    /**
     * 群岛扇区强度 mask（0~1）：纯几何锥形扇区，无噪声扭曲、无蔓延侵蚀。
     * <p>
     * 用户要求：内海不向任何方向侵蚀陆地。因此 mask 严格按扇区中心角 ± 半宽 的
     * 纯数学锥形返回，不做坐标扭曲、不做阈值蔓延，边界是一条笔直的切割线。
     */
    public static double islandSectorMask(double x, double z, Config cfg) {
        double base = sectorMask(ISLAND_SECTOR, x, z, cfg);
        if (base <= 0.0) {
            return 0.0;
        }
        // 纯几何，不做任何蔓延/侵蚀：直接返回 base 本身的平滑锥形
        return base;
    }

    /**
     * 群岛扇区实际半宽（弧度）：普通扇区半宽 + 群岛专用扩展量。
     * 内海/小岛/压低带/湿地带的全部群岛角度判定共用此值——只扩大群岛扇区，其他扇区仍用 {@link #sectorHalfWidthDeg}。
     */
    public static double islandSectorHalfRad() {
        return Math.toRadians(sectorHalfWidthDeg + islandSectorHalfExtraDeg);
    }

    /**
     * 群岛扇区角度掩码（0~1）：纯角度域硬窗口，无任何噪声扭曲、不外溢到相邻扇区。
     * <p>
     * 角度范围：[half*0.95, half*1.00] 是 5% 半宽的极窄 smoothstep 过渡（只做数值
     * 平滑避免绝对硬边产生跳变），超出 half*1.00 立即归零 → 保证压低带、湿地带、
     * 群系判定绝不会泄漏到群岛扇区两侧的相邻大陆。half 使用 {@link #islandSectorHalfRad()}（含扩展）。
     */
    public static double islandSectorAngMask(double x, double z) {
        double angle = Math.atan2(z, x);
        double center = sectorCenterAngle(ISLAND_SECTOR);
        double angOff = angle - center;
        double delta = Math.abs(Math.atan2(Math.sin(angOff), Math.cos(angOff)));
        double half = islandSectorHalfRad();
        double inner = half * 0.95;   // 内侧起点：离边界还有 5% 半宽
        double outer = half * 1.00;   // 外侧截止：严格等于扇区半宽，不外溢
        return 1.0 - Mth.smoothstep((float) Mth.clamp((delta - inner) / (outer - inner), 0.0, 1.0));
    }

    /**
     * 群岛扇区 falloff 场（0~1）：纯几何，角度不溢出、径向仅在环带内非零。
     * <p>
     * 用户要求：彻底禁止内海对两侧陆地的侵蚀。因此本函数移除所有坐标扭曲与
     * 角度向外延伸（原 loF-0.16 / hiF+0.16 会把压低带到相邻大陆），角度域由
     * {@link #islandSectorAngMask} 严格限制在扇区半宽范围内，径向域直接使用
     * sectorDistLo ~ sectorDistHi，不向内、不向外做额外扩展。
     */
    public static double islandSectorFalloff(double x, double z, Config cfg) {
        double angMask = islandSectorAngMask(x, z);
        if (angMask <= 0.0) {
            return 0.0;
        }
        double R = cfg.radius();
        double lo = R * sectorDistLo;
        double hi = R * sectorDistHi;
        // 纯几何径向窗口：严格在 sectorDistLo ~ sectorDistHi 之间
        // 每端用 sectorDist * 0.04 宽度做 smoothstep 过渡，避免数值跳变
        double loF = R * Math.max(0.0, sectorDistLo - 0.04);
        double hiF = R * Math.min(1.0, sectorDistHi + 0.04);
        double dist = Math.sqrt((double) x * x + (double) z * z);
        double radMask;
        if (dist <= loF || dist >= hiF) {
            radMask = 0.0;
        } else if (dist < lo) {
            radMask = Mth.smoothstep((float) ((dist - loF) / (lo - loF)));
        } else if (dist > hi) {
            radMask = 1.0 - Mth.smoothstep((float) ((dist - hi) / (hiF - hi)));
        } else {
            radMask = 1.0;
        }
        double f = angMask * radMask;
        // 群岛集中在环带中部：内/外缘权重低、环带中心权重高（与 islandSectorMask 一致）
        double rn = Mth.clamp((dist - lo) / (hi - lo), 0.0, 1.0);
        f *= Mth.smoothstep((float) (rn * (1.0 - rn) * 4.0));
        return Math.max(0.0, f);
    }

    /**
     * 群岛过渡带强度（0~1）：群岛扇区 mask 低于 0.30 的**窄**外缘环带
     * （mask 0.02~0.30，即扇区边缘到周围大陆之间的窄过渡带），mask 越低越靠外，强度越高。
     * mask ≥ 0.30（群岛主体/内海）返回 0——避免扭曲后的扇区大面积被误判为过渡带。
     */
    public static double islandTransitionWeight(double x, double z, Config cfg) {
        double islMask = islandSectorMask(x, z, cfg);
        return Mth.smoothstep((float) Mth.clamp((0.30 - islMask) / 0.28, 0.0, 1.0));
    }

    /**
     * 过渡带河网值（0~1）：1=河心沟槽，0=非河。
     * 域扭曲（±100 格）+ 双频噪声（50/18 格）取 0.5 附近的窄带（±0.05），
     * 产生密集、细窄、互相交错的河道网络——大量小河而非几条宽河。
     */
    public static double islandTransitionRiver(double x, double z) {
        double wx = x + (valueNoise(x, z, 120, 6101) - 0.5) * 200.0;
        double wz = z + (valueNoise(x, z, 120, 6102) - 0.5) * 200.0;
        double n1 = valueNoise(wx, wz, 50, 6103);
        double n2 = valueNoise(wx, wz, 18, 6104);
        double v = 0.6 * n1 + 0.4 * n2;
        double band = 0.05;
        double d = Math.abs(v - 0.5) / band;
        return 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
    }

    /** 供密度函数侧调用的重载（只关心大陆半径） */
    public static double sectorMask(int sector, double x, double z, double radius) {
        return sectorMask(sector, x, z, new Config((int) radius, 600, 400, 0.22));
    }

    /** 供密度函数侧调用的重载（只关心大陆半径；Config 中除 radius 外其余字段对群岛判定无影响） */
    public static double islandSectorMask(double x, double z, double radius) {
        return islandSectorMask(x, z, new Config((int) radius, 600, 400, 0.22));
    }

    /** 供密度函数侧调用的重载（只关心大陆半径；Config 中除 radius 外其余字段对群岛判定无影响） */
    public static double islandSectorFalloff(double x, double z, double radius) {
        return islandSectorFalloff(x, z, new Config((int) radius, 600, 400, 0.22));
    }

    /**
     * 群岛-环山带过渡湿地浅滩带（0~1）：径向 0.80R~0.98R 环带 × 群岛扇区角度掩码。
     * 群系源与地形侧 {@link ArchipelagoWetland} 共用同一判定，保证"地形见水"与
     * "群系为沼泽/红树林"永远对齐。
     * <p>
     * 边界形态：
     * <ul>
     *   <li>内缘（靠内海侧）：大幅噪声扭曲（±0.045R）且只向外弯——湿地带向内海方向
     *       伸出海湾/岬角，不向内收缩，形成犬牙交错的自然岸线</li>
     *   <li>外缘（靠环山带侧）：仅小幅扭曲（±0.015R），保持贴环山带墙脚的平顺衔接</li>
     *   <li>角度域：乘 {@link #islandSectorAngMask}，湿地带只在群岛扇区（扇区 2）出现，
     *       与地形侧 ArchipelagoWetland 的角度限制完全一致</li>
     * </ul>
     */
    public static double archipelagoWetlandBand(double x, double z, double radius) {
        double dist = Math.sqrt(x * x + z * z);
        // 内缘大幅扭曲且只向外（内海方向）延伸；外缘小幅扭曲保持贴环山带
        double warpIn = Math.min(0.0, (valueNoise(x, z, 280, 9200) - 0.5) * 2.0 * radius * 0.045);
        double warpOut = (valueNoise(x, z, 220, 9201) - 0.5) * 2.0 * radius * 0.015;
        double detIn = Math.min(0.0, (valueNoise(x, z, 60, 9202) - 0.5) * 2.0 * radius * 0.008);
        double detOut = (valueNoise(x, z, 80, 9203) - 0.5) * 2.0 * radius * 0.005;
        double lo = radius * 0.80 + warpIn + detIn;
        double hi = radius * 0.98 + warpOut + detOut;
        // 窄过渡带：主体区域 band≈1.0，仅边界平滑衰减（内缘 ~42 格、外缘 ~34 格）
        double inEdge = radius * 0.010;
        double outEdge = radius * 0.008;
        double band;
        if (dist <= lo - inEdge || dist >= hi + outEdge) {
            return 0.0;
        }
        if (dist < lo) {
            band = Mth.smoothstep((float) ((dist - (lo - inEdge)) / inEdge));
        } else if (dist > hi) {
            band = 1.0 - Mth.smoothstep((float) ((dist - hi) / outEdge));
        } else {
            band = 1.0;
        }
        // 群岛扇区角度限制：与 ArchipelagoWetland 地形侧共用 islandSectorAngMask，群系/地形严格对齐
        double angMask = islandSectorAngMask(x, z);
        if (angMask <= 0.0) {
            return 0.0;
        }
        // 【两侧收窄】用户要求"缩两侧"（角度方向）而非"前后"（径向），径向环带 0.80R~0.98R 保持不变。
        // 湿地带只在扇区中部满强度，向两侧（角度边界方向）提前衰减：half*0.80（16°）内满，
        // half*0.80~half*0.95（16°~19°）渐入为 0，两侧再往外走一点点但不到 20°。
        double angle = Math.atan2(z, x);
        double center = sectorCenterAngle(ISLAND_SECTOR);
        double delta = Math.abs(Math.atan2(Math.sin(angle - center), Math.cos(angle - center)));
        double half = islandSectorHalfRad(); // 含群岛专用扩展，湿地带跟随群岛扇区向两侧扩大
        double sideIn = half * 0.80;
        double sideOut = half * 0.95;
        double sideFade = 1.0 - Mth.smoothstep((float) Mth.clamp((delta - sideIn) / (sideOut - sideIn), 0.0, 1.0));
        return band * angMask * sideFade;
    }


    /**
     * 山脉扇区（扇区 0）的结构值（≥0）：群系源与 {@code MountainSector} 共用，保证群系与地形完全对齐。
     * <p>
     * 层层递增的弧线山脉：从外（环山带 0.955R）向内（0.30R）逐条同心弧线排列——
     * <ul>
     *   <li>8 条弧线，弧线是环向山脊（沿角度蜿蜒），相邻弧线之间是窄峡谷（谷底约 0.40）</li>
     *   <li>高度层层递增：越靠环山带越高（内弧峰 ≈0.45 → 外弧峰 ≈1.15），层间高差明显</li>
     *   <li>蜿蜒随外扩增强：内弧接近平直、外弧大幅蛇形弯曲 + 大尺度 S 弯，外弧是连续长脊</li>
     *   <li>每条弧线沿角度呈拱形：弧中心点最高，向两侧（扇区边缘）平滑递减</li>
     *   <li>阳坡/阴坡不对称：脊顶偏向环山带一侧，内侧（朝中心）为阳坡缓而长、外侧（朝环山带）为阴坡陡而短，两侧都平滑递减到谷底无断崖</li>
     *   <li>弧线径向是宽缓坡（外弧脊更宽更长）、峡谷窄；顶部叠加细节噪声 → 峰谷起伏，不是平顶</li>
     *   <li>中心（最内层弧线内侧）由深湖占据（湖盆降为 0，配合 DeepLakeSuppress/LakeBasin）</li>
     * </ul>
     */
    public static double mountainValue(double x, double z, double radius) {
        // 径向范围：0.30R（内）~ 0.955R（环山带），与环山带相接形成过渡
        double dist = Math.sqrt(x * x + z * z);
        double rLo = radius * 0.30;
        double rHi = radius * 0.955;
        if (dist <= rLo || dist >= rHi) {
            return 0.0;
        }
        double rn = Mth.clamp((dist - rLo) / (rHi - rLo), 0.0, 1.0); // 0=内 1=外

        // 角度差：蜿蜒随外扩增强 → 越靠环山带的山脉越弯
        // 内弧接近平直（±0.10 rad），外弧大幅蛇形弯曲（±0.40 rad）+ 小尺度边缘锯齿
        double warpAmp = 0.10 + 0.30 * rn;
        double warpBig = (valueNoise(x, z, 420, 4001) - 0.5) * 2.0 * warpAmp;
        double warpSmall = (valueNoise(x, z, 110, 4011) - 0.5) * 2.0 * (0.03 + 0.06 * rn);
        double angle = Math.atan2(z, x);
        double center = sectorCenterAngle(0);
        double delta = Math.abs(Math.atan2(Math.sin(angle + warpBig + warpSmall - center), Math.cos(angle + warpBig + warpSmall - center)));
        double half = Math.toRadians(sectorHalfWidthDeg);
        // 拱形角度 mask：弧中心点（角度中心）最高，向两侧平滑递减，边缘归零（缓坡过渡）
        double outer = half * 1.55;
        double angArch = 1.0 - Mth.smoothstep((float) Mth.clamp(delta / outer, 0.0, 1.0));
        if (angArch <= 0.0) {
            return 0.0;
        }
        double angMask = angArch * angArch; // 平方让中心更突出（点最高，两边小）

        // 弧线径向蜿蜒随外扩增强：短波蛇形 + 大尺度 S 弯（外弧形成连续蜿蜒的长脊）
        double wobbleAmp = 0.05 + 0.20 * rn;
        double wobble = (valueNoise(x, z, 300, 4005) - 0.5) * 2.0 * wobbleAmp;
        double wobbleLong = (valueNoise(x, z, 900, 4010) - 0.5) * 2.0 * (0.04 + 0.18 * rn);
        double n = 8.0;
        double phase = rn * n + wobble + wobbleLong;
        double seg = phase - Mth.floor(phase); // 0~1（当前弧线段内位置）
        // 阳坡/阴坡不对称：脊顶偏向环山带一侧（seg≈0.62）
        // 内侧 0~0.62 为阳坡（朝大陆中心，缓而长），外侧 0.62~1.0 为阴坡（朝环山带，陡而短），
        // 两侧余弦平滑 → 谷底与脊顶斜率都连续，无断崖
        double segC = 0.62;
        double asym = seg < segC
            ? 0.5 * (seg / segC)
            : 0.5 + 0.5 * ((seg - segC) / (1.0 - segC));
        double arcShape = 0.5 - 0.5 * Math.cos(asym * Math.PI * 2.0); // 0~1，脊顶在 seg=segC
        // 脊宽谷窄：外层弧脊更宽更长（指数随外扩增大），峡谷收窄（谷底 V 形）
        double ridge = 1.0 - Math.pow(1.0 - arcShape, 1.2 + 0.8 * rn);

        // 弧线峰值：越接近环山带越高，层层递增（层间高差更明显）
        double i = Mth.floor(phase);
        double rnCenter = Mth.clamp((i + 0.5) / n, 0.0, 1.0);
        double peak = 0.40 + 0.75 * rnCenter; // 外弧≈1.15、内弧≈0.45

        // 弧线顶部细节：让山脊有峰谷（不是平滑平顶），幅度略低于层间高差
        double detail = (valueNoise(x, z, 120, 4006) - 0.5) * 0.28 * ridge;
        double detail2 = (valueNoise(x, z, 40, 4007) - 0.5) * 0.10 * ridge;

        // 高度 = 峡谷底 + 弧线大幅提升 + 细节起伏
        // 峰值 ~2.47 ×0.32（换算 Y ≈ 128 + 128×offset → 最外弧 ≈258、最内弧 ≈207）
        double m = 0.40 + ridge * peak * 1.70 + detail + detail2;
        // 不平顶、不硬限高：只乘 angMask 控制横向衰减，不做 min/max 裁剪
        return Math.max(0.0, m * angMask);
    }

    /**
     * 群岛扇区的大陆度：小岛为陆地、岛间为深海（内海）。
     * 岛屿为中等大小（102~162 格），带中等域扭曲（warp 0.55r，双频噪声）——
     * 形状完整不规则、边缘不破碎；35% 单元格概率 + 岛心偏移 ±0.40 格（≈±120 格）
     * → 岛心偏离格子中心，弱化规整方格感，水岛相间。
     */
    public static double islandSectorValue(double x, double z) {
        // 海洋神殿保留区：强制深海（-0.9），清除区域内所有岛屿，保证神殿周围干净
        if (isInMonumentClear(x, z)) {
            return -0.9;
        }
        double cell = 300.0;
        long cx0 = Mth.lfloor(x / cell);
        long cz0 = Mth.lfloor(z / cell);
        // 【3×3 邻域搜索】岛心偏移 ±0.40 格（≈±120 格）+ 半径最大 0.54 格（≈162 格），
        // 岛身会越出本格最多约 130 格；若只查当前格，越界的岛身会被"切除"成方块缺口。
        // 岛心最多落在检查点所在格的 ±1 格范围内，3×3 邻域必然覆盖——
        // 与外海岛屿（bias 外围 3×3）及 innerIslandOwner 的几何完全一致，地形/群系统一。
        double best = -0.78; // 岛间深海基线
        for (long dxx = -1; dxx <= 1; dxx++) {
            for (long dzz = -1; dzz <= 1; dzz++) {
                long ccx = cx0 + dxx;
                long ccz = cz0 + dzz;
                // 群岛概率 0.35：水岛相间较稀疏（蘑菇岛格强制生成）
                if (!(hash(ccx, ccz, 909) < 0.35 || (ccx == mushroomCellX && ccz == mushroomCellZ))) {
                    continue;
                }
                // 岛中心偏移 ±0.40 格（≈±120 格），偏离格心但基本留在本格
                double ox = (ccx + 0.5 + (hash(ccx, ccz, 404) - 0.5) * 0.8) * cell;
                double oz = (ccz + 0.5 + (hash(ccx, ccz, 505) - 0.5) * 0.8) * cell;
                double r = cell * (0.34 + 0.20 * hash(ccx, ccz, 606));
                // 域扭曲（双频噪声叠加）：让岛屿边缘不规则但整体形状完整，避免破碎成碎块
                double warp = r * 0.55;
                double wx = x + (valueNoise(x, z, 90, 1001) - 0.5) * 2.0 * warp
                              + (valueNoise(x, z, 36, 1003) - 0.5) * 1.4 * warp;
                double wz = z + (valueNoise(x, z, 90, 1002) - 0.5) * 2.0 * warp
                              + (valueNoise(x, z, 36, 1004) - 0.5) * 1.4 * warp;
                double d = Math.sqrt((wx - ox) * (wx - ox) + (wz - oz) * (wz - oz)) / r;
                if (d < 1.0) {
                    double e = 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
                    double v = -0.55 + 0.85 * e; // 中心约 0.30（陆地），边缘滑向深海
                    if (v > best) {
                        best = v;
                    }
                }
            }
        }
        return best;
    }

    /** 群岛扇区该位置是否为小岛陆地（与 {@link #islandSectorValue} 的陆地阈值完全一致） */
    public static boolean islandSectorIsLand(double x, double z) {
        return islandSectorValue(x, z) >= LAND_BIAS_THRESHOLD;
    }

    /** 群岛小岛所在单元格的确定性哈希（0~1），供群系源随机选取岛群系 */
    public static double islandSectorHash(double x, double z, int seed) {
        long cx = Mth.lfloor(x / 300.0);
        long cz = Mth.lfloor(z / 300.0);
        return hash(cx, cz, seed);
    }

    /** 平滑 2D 值噪声（双线性插值），输出 0~1 */
    public static double valueNoise(double x, double z, int cell, int seed) {
        double xc = x / cell;
        double zc = z / cell;
        long x0 = Mth.lfloor(xc);
        long z0 = Mth.lfloor(zc);
        double fx = xc - x0;
        double fz = zc - z0;
        double sx = Mth.smoothstep(fx);
        double sz = Mth.smoothstep(fz);
        double h00 = hash(x0, z0, seed);
        double h10 = hash(x0 + 1, z0, seed);
        double h01 = hash(x0, z0 + 1, seed);
        double h11 = hash(x0 + 1, z0 + 1, seed);
        return Mth.lerp(sz, Mth.lerp(sx, h00, h10), Mth.lerp(sx, h01, h11));
    }

    /** 确定性哈希，输出 0~1（混入世界种子，不同种子图案不同） */
    public static double hash(long x, long z, int seed) {
        long h = x * 341873128712L + z * 132897987541L + seed * 9029L + worldSeedMix;
        h = (h ^ (h >>> 16)) * 0x45d9f3bL;
        h = (h ^ (h >>> 16)) * 0x45d9f3bL;
        h ^= h >>> 16;
        return (h & 0x7fffffffL) / (double) 0x7fffffffL;
    }
    /** 内海群岛小岛所有者格（与 {@link #islandSectorValue} 完全一致的几何判定）。
     *  返回命中岛的所属格 [cx, cz]；若未命中任何小岛，返回当前格 [lfloor(x/300), lfloor(z/300)]。
     *  <p>目的：岛心可偏移 ±0.40 格（≈±120 格），同一岛屿可能横跨 2~3 个网格单元；
     *  若直接按当前格哈希取群系，同一岛会被切割成多群系拼贴（用户图四现象）。
     *  通过 3×3 邻域搜索「真正生成该岛的格子」保证一岛一哈希一生物群系。 */
    public static long[] innerIslandOwner(double x, double z) {
        double cell = 300.0;
        long cx0 = Mth.lfloor(x / cell);
        long cz0 = Mth.lfloor(z / cell);
        long ownerCx = cx0;
        long ownerCz = cz0;
        double bestValue = -1.0;
        for (long dxx = -1; dxx <= 1; dxx++) {
            for (long dzz = -1; dzz <= 1; dzz++) {
                long ccx = cx0 + dxx;
                long ccz = cz0 + dzz;
                if (!(hash(ccx, ccz, 909) < 0.35 || (ccx == mushroomCellX && ccz == mushroomCellZ))) {
                    continue;
                }
                double ox = (ccx + 0.5 + (hash(ccx, ccz, 404) - 0.5) * 0.8) * cell;
                double oz = (ccz + 0.5 + (hash(ccx, ccz, 505) - 0.5) * 0.8) * cell;
                double r = cell * (0.34 + 0.20 * hash(ccx, ccz, 606));
                double warp = r * 0.55;
                double wx = x + (valueNoise(x, z, 90, 1001) - 0.5) * 2.0 * warp
                                + (valueNoise(x, z, 36, 1003) - 0.5) * 1.4 * warp;
                double wz = z + (valueNoise(x, z, 90, 1002) - 0.5) * 2.0 * warp
                                + (valueNoise(x, z, 36, 1004) - 0.5) * 1.4 * warp;
                double d = Math.sqrt((wx - ox) * (wx - ox) + (wz - oz) * (wz - oz)) / r;
                if (d < 1.0) {
                    double e = 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
                    double val = -0.55 + 0.85 * e;
                    if (val > bestValue) {
                        bestValue = val;
                        ownerCx = ccx;
                        ownerCz = ccz;
                    }
                }
            }
        }
        return new long[]{ownerCx, ownerCz};
    }

    /** 外海岛屿所有者格（与 bias() 外围岛屿生成的「best-value」准则完全一致）。
     *  返回命中岛的所属格 [cx, cz]；若未命中，返回当前格。与 {@link #innerIslandOwner} 同理：
     *  岛心偏移 ±0.60 格 + 半径 0.38~0.56grid 会让同一岛屿跨越多个网格单元，必须统一所有者哈希。 */
    public static long[] farIslandOwner(double x, double z, Config cfg) {
        double dist = Math.sqrt(x * x + z * z);
        double baseChance = cfg.islandChance();
        double radiusScale = 1.0;
        if (dist >= cfg.radius() * farIslandStartMul) {
            baseChance *= farIslandMul;
            radiusScale = 0.85 + 0.40 * farIslandMul;
        }
        double effectiveChance = Mth.clamp(baseChance, 0.0, 1.0);
        long cx0 = cellX(x, cfg);
        long cz0 = cellZ(z, cfg);
        long ownerCx = cx0;
        long ownerCz = cz0;
        double bestValue = -2.0;
        double g = cfg.grid();
        if (effectiveChance > 0.0) {
            for (long dxx = -1; dxx <= 1; dxx++) {
                for (long dzz = -1; dzz <= 1; dzz++) {
                    long ccx = cx0 + dxx;
                    long ccz = cz0 + dzz;
                    if (hash(ccx, ccz, 303) >= effectiveChance) continue;
                    double ox = (ccx + 0.5 + (hash(ccx, ccz, 404) - 0.5) * 1.2) * g;
                    double oz = (ccz + 0.5 + (hash(ccx, ccz, 505) - 0.5) * 1.2) * g;
                    double r = g * (0.38 + 0.18 * hash(ccx, ccz, 606)) * radiusScale;
                    double warp = r * 0.35;
                    double wx = x + (valueNoise(x, z, 90, 1001) - 0.5) * 2.0 * warp;
                    double wz = z + (valueNoise(x, z, 90, 1002) - 0.5) * 2.0 * warp;
                    double rr = (wx - ox) * (wx - ox) + (wz - oz) * (wz - oz);
                    if (rr < r * r) {
                        double d = Math.sqrt(rr) / r;
                        double e = 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
                        double v = -0.90 + 1.50 * e;
                        if (v > bestValue) {
                            bestValue = v;
                            ownerCx = ccx;
                            ownerCz = ccz;
                        }
                    }
                }
            }
        }
        return new long[]{ownerCx, ownerCz};
    }

    /** 该位置是否命中外海岛屿陆地几何（所有者格搜索，与 bias 外围岛屿 best-value 一致）。
     *  作用：即使 dist < radius+transition（海岸带），若点已落入外海岛屿几何，仍按外海岛判定。 */
    public static boolean isOuterIslandLand(double x, double z, Config cfg) {
        long[] owner = farIslandOwner(x, z, cfg);
        long ocx = owner[0];
        long ocz = owner[1];
        double dist = Math.sqrt(x * x + z * z);
        double baseChance = cfg.islandChance();
        double radiusScale = 1.0;
        if (dist >= cfg.radius() * farIslandStartMul) {
            baseChance *= farIslandMul;
            radiusScale = 0.85 + 0.40 * farIslandMul;
        }
        double eff = Mth.clamp(baseChance, 0.0, 1.0);
        if (eff <= 0.0) return false;
        if (hash(ocx, ocz, 303) >= eff) return false;
        double g = cfg.grid();
        double ox = (ocx + 0.5 + (hash(ocx, ocz, 404) - 0.5) * 1.2) * g;
        double oz = (ocz + 0.5 + (hash(ocx, ocz, 505) - 0.5) * 1.2) * g;
        double r = g * (0.38 + 0.18 * hash(ocx, ocz, 606)) * radiusScale;
        double warp = r * 0.35;
        double wx = x + (valueNoise(x, z, 90, 1001) - 0.5) * 2.0 * warp;
        double wz = z + (valueNoise(x, z, 90, 1002) - 0.5) * 2.0 * warp;
        double rr = (wx - ox) * (wx - ox) + (wz - oz) * (wz - oz);
        if (rr >= r * r) return false;
        double d = Math.sqrt(rr) / r;
        double e = 1.0 - Mth.smoothstep((float) Mth.clamp(d, 0.0, 1.0));
        double v = -0.90 + 1.50 * e;
        return v >= LAND_BIAS_THRESHOLD;
    }
}
