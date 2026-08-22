package com.cai.continents_and_isles;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        NeoForge.EVENT_BUS.addListener(ContinentsAndIslesMod::onServerAboutToStart);

        // 【开发调试】启动前按需删除配置文件，必须在 registerConfig 之前执行。
        tryResetConfigBeforeRegister();

        modContainer.registerConfig(ModConfig.Type.COMMON, CAIConfig.SPEC);
    }

    /**
     * 在 registerConfig 之前判断是否需要强制删除 config/continents_and_isles.toml。
     * 不把"是否重置"开关写进 toml 本身（避免鸡生蛋问题，且配置文件只保留业务配置）。
     * 跳过重置的两种方式（都不需要写进 continents_and_isles.toml）：
     *   1. JVM 参数 -Dcai.keep.config=true            → 临时保留一次（启动参数加即可）
     *   2. config/cai_keep_config.marker 文件存在      → 永久保留（touch 一个空文件）
     * 其他情况：只要 continents_and_isles.toml 存在就删除，registerConfig 会以
     * CAIConfig.java 中的最新默认值自动重新生成。
     */
    private static void tryResetConfigBeforeRegister() {
        // 方式 1：JVM 覆盖（最高优先级）
        if (Boolean.getBoolean("cai.keep.config")) {
            System.out.println("[ContinentsAndIsles] 跳过配置重置（JVM 参数 cai.keep.config=true）");
            return;
        }
        Path configDir = FMLPaths.CONFIGDIR.get();
        // 方式 2：marker 文件永久保留
        Path markerFile = configDir.resolve("cai_keep_config.marker");
        if (Files.exists(markerFile)) {
            System.out.println("[ContinentsAndIsles] 跳过配置重置（marker 文件存在：cai_keep_config.marker）");
            return;
        }
        Path configFile = configDir.resolve("continents_and_isles.toml");
        if (!Files.exists(configFile)) {
            return; // 不存在则 registerConfig 自动创建，无需操作
        }
        try {
            Files.delete(configFile);
            System.out.println("[ContinentsAndIsles] 已重置配置：删除 continents_and_isles.toml，"
                + "registerConfig 将用 CAIConfig.java 的最新默认值重新生成。");
        } catch (IOException e) {
            System.err.println("[ContinentsAndIsles] 删除配置文件失败: " + e.getMessage());
        }
    }

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        ContinentIslandField.loadConfig();
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
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "archipelago_wetland"),
                    () -> ArchipelagoWetland.CODEC.codec()
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
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "archipelago_erosion_suppress"),
                    () -> ArchipelagoErosionSuppress.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "archipelago_transition"),
                    () -> ArchipelagoTransition.CODEC.codec()
            );
            event.register(
                    Registries.DENSITY_FUNCTION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "flat_64"),
                    () -> Flat64.CODEC.codec()
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