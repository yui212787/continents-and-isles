package com.cai.continents_and_isles;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置：在 config/continents_and_isles.toml 中自动生成。
 * <p>
 * 修改配置后需重新创建世界才生效（世界生成参数在创建时固定）。
 */
public final class CAIConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ── 超大陆 ──
    public static final ModConfigSpec.IntValue RADIUS;
    public static final ModConfigSpec.IntValue TRANSITION;

    // ── 外围岛屿 ──
    public static final ModConfigSpec.IntValue GRID;
    public static final ModConfigSpec.DoubleValue ISLAND_CHANCE;
    // 外围远距离岛屿：离超大陆多远（按超大陆半径倍数）开始进入"远海区"
    // 远海区使用单独的生成倍率，保证更远的海洋也有岛（默认 1.0x 不衰减）
    public static final ModConfigSpec.DoubleValue FAR_ISLAND_START_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue FAR_ISLAND_CHANCE_MULTIPLIER;

    // ── 必生成大湖 ──
    public static final ModConfigSpec.DoubleValue LAKE_RADIUS;
    public static final ModConfigSpec.DoubleValue LAKE_CENTER_FRACTION;
    // 三个大湖是否生成的独立开关
    public static final ModConfigSpec.BooleanValue LAKE_0_ENABLED; // 东湖（岛湖）
    public static final ModConfigSpec.BooleanValue LAKE_1_ENABLED; // 山脉扇区湖（深湖+天池）
    public static final ModConfigSpec.BooleanValue LAKE_2_ENABLED; // 随机扇区湖（群系湖）

    // ── 固定扇区 ──
    public static final ModConfigSpec.DoubleValue SECTOR_HALF_WIDTH;
    public static final ModConfigSpec.DoubleValue SECTOR_DIST_LO;
    public static final ModConfigSpec.DoubleValue SECTOR_DIST_HI;

    // ── 扇区主群系 / 附属群系 + 占比 ──
    // 扇区索引含义（ContinentIslandField 定义）：0=山脉, 1=丛林, 2=群岛, 3=沙漠, 4=热带草原, 5=雪原
    // 山脉扇区（0）与群岛扇区（2）有专属分级/内海逻辑，主群系只作为兜底，附属群系作为点缀。
    // 群系格式："namespace:path"，例如 "minecraft:jungle"；不填或填错则回退到硬编码默认值。
    public static final ModConfigSpec.ConfigValue<String> SECTOR_0_MAIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECTOR_0_EXTRAS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SECTOR_0_EXTRA_WEIGHTS;

    public static final ModConfigSpec.ConfigValue<String> SECTOR_1_MAIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECTOR_1_EXTRAS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SECTOR_1_EXTRA_WEIGHTS;

    public static final ModConfigSpec.ConfigValue<String> SECTOR_2_MAIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECTOR_2_EXTRAS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SECTOR_2_EXTRA_WEIGHTS;

    public static final ModConfigSpec.ConfigValue<String> SECTOR_3_MAIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECTOR_3_EXTRAS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SECTOR_3_EXTRA_WEIGHTS;

    public static final ModConfigSpec.ConfigValue<String> SECTOR_4_MAIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECTOR_4_EXTRAS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SECTOR_4_EXTRA_WEIGHTS;

    public static final ModConfigSpec.ConfigValue<String> SECTOR_5_MAIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SECTOR_5_EXTRAS;
    public static final ModConfigSpec.ConfigValue<List<? extends Double>> SECTOR_5_EXTRA_WEIGHTS;

    // ── 边缘环山 ──
    public static final ModConfigSpec.BooleanValue RING_MOUNTAIN_ENABLED;

    // ── 外岛群系黑名单：这些群系不会出现在超大陆之外的岛屿上（大陆扇区/湖/群岛扇区内部不受影响）
    public static final ModConfigSpec.ConfigValue<List<? extends String>> OUTER_ISLAND_BIOME_BLACKLIST;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
            "大陆与群岛 — 世界生成配置",
            "修改后需重新创建世界才生效（已生成的世界不受影响）"
        ).push("continents");

        RADIUS = BUILDER.comment("超大陆半径（格）")
                        .defineInRange("radius", 4250, 500, 20000);
        TRANSITION = BUILDER.comment("海岸过渡带宽度（格）")
                            .defineInRange("transition", 160, 50, 5000);
        BUILDER.pop();

        BUILDER.comment(
            "外围岛屿（超大陆之外）。",
            "默认：即使离超大陆很远也会持续生成岛屿——没有\"越远就越不生成\"的设定。",
            "如果你希望远海更多/更少岛屿，调整 far_island_chance_multiplier：",
            "  = 1.0 → 远近岛屿生成概率相同",
            "  > 1.0 → 远海岛屿更多更大",
            "  < 1.0 → 远海岛屿更少",
            "  = 0.0 → 超过 start_multiplier 半径后完全没有岛"
        ).push("islands");
        GRID = BUILDER.comment("外围岛屿网格单元大小（格）")
                      .defineInRange("grid", 400, 100, 2000);
        ISLAND_CHANCE = BUILDER.comment("每个网格单元生成岛屿的概率（0~1，近海外围），",
                                        "默认较 0.32 显著提高 → 0.45，使外岛更密集")
                               .defineInRange("island_chance", 0.45, 0.0, 1.0);
        FAR_ISLAND_START_MULTIPLIER = BUILDER
            .comment("远海区起点（按超大陆半径的倍数，不含海岸过渡带）。",
                     "达到该距离后启用远海单独生成倍率。默认 2.5R ≈ 一万格之外。")
            .defineInRange("far_island_start_multiplier", 2.5, 1.0, 50.0);
        FAR_ISLAND_CHANCE_MULTIPLIER = BUILDER
            .comment("远海区岛屿生成概率倍率。",
                     "1.0 = 远近相同；>1 = 远海更多；<1 = 远海更少；0 = 远海无岛。",
                     "默认 1.35 → 远海额外加密约 35%，岛屿也略大。")
            .defineInRange("far_island_chance_multiplier", 1.35, 0.0, 3.0);
        OUTER_ISLAND_BIOME_BLACKLIST = BUILDER
            .comment("外岛群系黑名单（资源定位符），被列出的群系不会出现在超大陆之外的岛屿上。",
                     "大陆扇区、三湖、群岛扇区内部的小岛、山脉/雪原分级群系不受影响。",
                     "示例：[\"minecraft:cherry_grove\", \"minecraft:ice_spikes\"]")
            .defineList("outer_island_biome_blacklist",
                List.of(),
                o -> o instanceof String);
        BUILDER.pop();

        BUILDER.push("lake");
        LAKE_RADIUS = BUILDER.comment("必生成大湖的基础半径（格），实际每个湖会在此附近随机浮动")
                             .defineInRange("lake_radius", 190.0, 50.0, 1000.0);
        LAKE_CENTER_FRACTION = BUILDER.comment("大湖（东湖）中心位置（占大陆半径的比例）")
                                      .defineInRange("lake_center_fraction", 0.19, 0.0, 0.5);
        LAKE_0_ENABLED = BUILDER.comment("生成东湖（固定在出生点东侧的岛湖，带大量小岛）")
                                .define("lake_east_enabled", true);
        LAKE_1_ENABLED = BUILDER.comment("生成山脉扇区湖（深湖，约一半概率为天池环形山）")
                                .define("lake_mountain_enabled", true);
        LAKE_2_ENABLED = BUILDER.comment("生成随机扇区湖（群系湖，湖面随机从 10 种群系中抽取）")
                                .define("lake_biome_enabled", true);
        BUILDER.pop();

        BUILDER.comment(
            "六大固定扇区（方位按世界种子随机旋转，但对立关系固定）：",
            "  0 = 山脉扇区：山脚→山腰→峰顶分级（硬编码逻辑，主群系为兜底）",
            "  1 = 丛林扇区",
            "  2 = 群岛扇区：内海+小岛（硬编码专属逻辑，主群系为内海面兜底）",
            "  3 = 沙漠扇区：默认主沙漠 + 20~30% 恶地",
            "  4 = 热带草原扇区",
            "  5 = 雪原扇区",
            "每个扇区可自定义一个主群系 + 若干附属群系及其权重。",
            "权重是相对比例（不必加和等于 1）；剩余概率自动归主群系。",
            "例：沙漠扇区 extras=[\"minecraft:badlands\",\"minecraft:eroded_badlands\"], ",
            "      extra_weights=[0.18, 0.07] → 约 25% 恶地家族、75% 沙漠。"
        ).push("sectors");
        SECTOR_HALF_WIDTH = BUILDER.comment("固定扇区半宽（度）")
                                   .defineInRange("sector_half_width", 20.0, 5.0, 30.0);
        SECTOR_DIST_LO = BUILDER.comment("扇区环带内径（占大陆半径的比例）")
                                .defineInRange("sector_dist_lo", 0.30, 0.0, 0.8);
        SECTOR_DIST_HI = BUILDER.comment("扇区环带外径（占大陆半径的比例）")
                                .defineInRange("sector_dist_hi", 0.85, 0.2, 1.0);

        // 扇区 0 山脉（分级逻辑为主，主群系仅用于极低 mask 兜底，附属群系作为山麓点缀）
        SECTOR_0_MAIN = BUILDER.comment("扇区 0（山脉）主群系（仅兜底，大部分仍按山高分带）")
                               .define("sector_0_main", "minecraft:meadow");
        SECTOR_0_EXTRAS = BUILDER.comment("扇区 0 附属群系列表（按资源定位符）")
                                 .defineList("sector_0_extras",
                                     List.of(),
                                     o -> o instanceof String);
        SECTOR_0_EXTRA_WEIGHTS = BUILDER.comment("扇区 0 附属群系的对应权重（数量须与 extras 一致）")
                                        .defineList("sector_0_extra_weights",
                                            List.of(),
                                            o -> o instanceof Double);

        // 扇区 1 丛林（雨林）：红树林/沼泽作为雨林附属群系（红树林约 10%、沼泽约 20%）
        SECTOR_1_MAIN = BUILDER.comment("扇区 1（丛林/雨林）主群系")
                               .define("sector_1_main", "minecraft:jungle");
        SECTOR_1_EXTRAS = BUILDER.comment("扇区 1 附属群系列表（按资源定位符），",
                                         "红树林与沼泽作为雨林附属群系生成")
                                 .defineList("sector_1_extras",
                                     List.of("minecraft:sparse_jungle",
                                             "minecraft:bamboo_jungle",
                                             "minecraft:mangrove_swamp",
                                             "minecraft:swamp"),
                                     o -> o instanceof String);
        SECTOR_1_EXTRA_WEIGHTS = BUILDER.comment("扇区 1 附属群系的对应权重（数量须与 extras 一致）；",
                                                 "默认：稀疏丛林约 10%、竹林约 4%、红树林约 10%、沼泽约 20%")
                                        .defineList("sector_1_extra_weights",
                                            List.of(0.18, 0.08, 0.18, 0.36),
                                            o -> o instanceof Double);

        // 扇区 2 群岛（专属内海/小岛逻辑，主群系仅兜底，附属群系作为小岛额外点缀）
        SECTOR_2_MAIN = BUILDER.comment("扇区 2（群岛）主群系（仅兜底，实际为内海+小岛随机群系）")
                               .define("sector_2_main", "minecraft:ocean");
        SECTOR_2_EXTRAS = BUILDER.comment("扇区 2 附属群系列表（按资源定位符）")
                                 .defineList("sector_2_extras",
                                     List.of(),
                                     o -> o instanceof String);
        SECTOR_2_EXTRA_WEIGHTS = BUILDER.comment("扇区 2 附属群系的对应权重（数量须与 extras 一致）")
                                        .defineList("sector_2_extra_weights",
                                            List.of(),
                                            o -> o instanceof Double);

        // 扇区 3 沙漠（默认主沙漠 + 20~30% 恶地家族）
        SECTOR_3_MAIN = BUILDER.comment("扇区 3（沙漠）主群系")
                               .define("sector_3_main", "minecraft:desert");
        SECTOR_3_EXTRAS = BUILDER.comment("扇区 3 附属群系列表（按资源定位符）")
                                 .defineList("sector_3_extras",
                                     List.of("minecraft:badlands",
                                             "minecraft:eroded_badlands",
                                             "minecraft:wooded_badlands",
                                             "minecraft:forest"),
                                     o -> o instanceof String);
        SECTOR_3_EXTRA_WEIGHTS = BUILDER.comment("扇区 3 附属群系的对应权重（数量须与 extras 一致）；",
                                                 "默认：恶地丘陵 0.15 + 侵蚀恶地（陶瓦尖塔）0.07 + 繁茂恶地 0.03 + 绿洲小树林 0.04",
                                                 "合计约 29% 附属群系、71% 主沙漠")
                                        .defineList("sector_3_extra_weights",
                                            List.of(0.15, 0.07, 0.03, 0.04),
                                            o -> o instanceof Double);

        // 扇区 4 热带草原
        SECTOR_4_MAIN = BUILDER.comment("扇区 4（热带草原）主群系")
                               .define("sector_4_main", "minecraft:savanna");
        SECTOR_4_EXTRAS = BUILDER.comment("扇区 4 附属群系列表（按资源定位符）")
                                 .defineList("sector_4_extras",
                                     List.of("minecraft:savanna_plateau", "minecraft:windswept_savanna"),
                                     o -> o instanceof String);
        SECTOR_4_EXTRA_WEIGHTS = BUILDER.comment("扇区 4 附属群系的对应权重")
                                        .defineList("sector_4_extra_weights",
                                            List.of(0.20, 0.05),
                                            o -> o instanceof Double);

        // 扇区 5 雪原
        SECTOR_5_MAIN = BUILDER.comment("扇区 5（雪原）主群系")
                               .define("sector_5_main", "minecraft:snowy_plains");
        SECTOR_5_EXTRAS = BUILDER.comment("扇区 5 附属群系列表（按资源定位符）",
                                         "注意：冰刺平原仅在雪原扇区允许出现，其他扇区强制排除")
                                 .defineList("sector_5_extras",
                                     List.of("minecraft:ice_spikes", "minecraft:snowy_taiga"),
                                     o -> o instanceof String);
        SECTOR_5_EXTRA_WEIGHTS = BUILDER.comment("扇区 5 附属群系的对应权重；",
                                                 "概率 = 权重/(1+权重和)：雪原针叶林约 14%、冰刺平原约 2%、主雪原约 84%")
                                        .defineList("sector_5_extra_weights",
                                            List.of(0.024, 0.166),
                                            o -> o instanceof Double);
        BUILDER.pop();

        BUILDER.push("ring_mountain");
        RING_MOUNTAIN_ENABLED = BUILDER.comment("是否在超大陆边缘生成环形山脉（默认开启）",
                                               "开启后整个超大陆被高山环绕，外侧断崖入海",
                                               "修改后需重新创建世界才生效")
                                       .define("ring_mountain_enabled", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private CAIConfig() {
    }
}
