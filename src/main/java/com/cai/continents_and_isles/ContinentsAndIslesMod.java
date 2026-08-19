package com.cai.continents_and_isles;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Continents and Isles —— 在世界创建时选择一个「大陆与群岛」世界类型：
 * 中央一片连起来的超大陆 + 外围深海散布岛屿。
 *
 * 地形与群系由自定义密度函数 {@link RadialLand} 驱动，具体参数与预设配置
 * 位于 mod 内置的数据包：
 * <pre>
 * data/continents_and_isles/worldgen/density_function/overworld/continents.json
 * data/continents_and_isles/worldgen/noise_settings/overworld.json
 * data/continents_and_isles/worldgen/world_preset/continents_and_isles.json
 * </pre>
 */
@Mod(ContinentsAndIslesMod.MOD_ID)
public class ContinentsAndIslesMod {
    public static final String MOD_ID = "continents_and_isles";

    public ContinentsAndIslesMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(ContinentsAndIslesMod::registerWorldgenTypes);
        // 扇区旋转角由世界种子决定：每个新世界扇区方位随机，但对立关系不变
        NeoForge.EVENT_BUS.addListener(ContinentsAndIslesMod::onServerAboutToStart);
        // 注册配置文件 config/continents_and_isles.toml
        modContainer.registerConfig(ModConfig.Type.COMMON, CAIConfig.SPEC);
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        // 必须先加载配置再初始化：initLake 里要用 lakeEnabled 判断湖是否启用，
        // 若先 initSectorRotation，lakeEnabled 还是默认全 false，三个湖会被全部归零。
        ContinentIslandField.loadConfig();
        // 扇区旋转角由世界种子决定：每个新世界扇区方位随机，但对立关系不变
        ContinentIslandField.initSectorRotation(seed);
    }

    private static void registerWorldgenTypes(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.DENSITY_FUNCTION_TYPE)) {
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "radial_land"),
                    () -> RadialLand.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "mountain_sector"),
                    () -> MountainSector.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "deep_lake"),
                    () -> DeepLake.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "ring_mountain"),
                    () -> RingMountain.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "tianchi_mountain"),
                    () -> TianchiMountain.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "lake_basin"),
                    () -> LakeBasin.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "deep_lake_suppress"),
                    () -> DeepLakeSuppress.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "island_lowland"),
                    () -> IslandLowland.CODEC.codec()
            );
        }
        if (event.getRegistryKey().equals(Registries.BIOME_SOURCE)) {
            event.register(
                    Registries.BIOME_SOURCE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "continents_and_isles"),
                    () -> ContinentsAndIslesBiomeSource.CODEC
            );
        }
        if (event.getRegistryKey().equals(Registries.STRUCTURE_TYPE)) {
            event.register(
                    Registries.STRUCTURE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "ocean_monument"),
                    () -> ContinentsIslandMonumentStructure.TYPE
            );
            event.register(
                    Registries.STRUCTURE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "swamp_hut"),
                    () -> ContinentsIslandSwampHutStructure.TYPE
            );
            event.register(
                    Registries.STRUCTURE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "desert_pyramid"),
                    () -> ContinentsIslandDesertPyramidStructure.TYPE
            );
            event.register(
                    Registries.STRUCTURE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "woodland_mansion"),
                    () -> ContinentsIslandWoodlandMansionStructure.TYPE
            );
        }
        if (event.getRegistryKey().equals(Registries.FEATURE)) {
            event.register(
                    Registries.FEATURE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "ring_mountain_ore"),
                    () -> new RingMountainOreFeature(net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration.CODEC)
            );
        }
    }
}
