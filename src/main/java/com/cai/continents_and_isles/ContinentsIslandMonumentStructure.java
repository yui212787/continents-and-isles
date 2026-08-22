package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;

import java.util.Optional;

/**
 * 群岛扇区内的定点海洋神殿。
 * <p>
 * 原版 {@link OceanMonumentStructure} 由 structure_set 的 random_spread 网格决定候选位置
 * （spacing=32 chunk，且要求候选点周围 29 格群系全为深海），在超大陆世界里几乎不生成。
 * 本结构复用原版神殿的全部生成逻辑（{@link OceanMonumentPieces.MonumentBuilding}），
 * 但把生成位置固定为 {@link ContinentIslandField#monumentChunkPos()}（群岛扇区中的神殿保留区中心）：
 * <ul>
 *   <li>structure_set 用 spacing=1 / separation=1，保证每个 chunk 都会被判定到</li>
 *   <li>{@link #findGenerationPoint} 只接受"恰好等于保留区中心 chunk"的候选点，其余返回 empty</li>
 *   <li>中心点做与原版一致的深海群系检查（{@link BiomeTags#REQUIRED_OCEAN_MONUMENT_SURROUNDING}）</li>
 * </ul>
 * 保留区地形由 {@link ContinentIslandField} 保证：半径 300 格（MONUMENT_CLEAR_RADIUS）内无岛屿、深海，
 * 群系为 deep ocean，因此神殿必定且仅生成一处，且周围没有岛屿贴近。
 */
public class ContinentsIslandMonumentStructure extends OceanMonumentStructure {

    public static final MapCodec<ContinentsIslandMonumentStructure> CODEC =
        simpleCodec(ContinentsIslandMonumentStructure::new);

    public static final StructureType<ContinentsIslandMonumentStructure> TYPE = () -> CODEC;

    public ContinentsIslandMonumentStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos center = ContinentIslandField.monumentChunkPos();
        // 只有恰好是保留区中心 chunk 的候选点才生成（spacing=1 保证每个 chunk 都被判定）
        if (center == null || !context.chunkPos().equals(center)) {
            return Optional.empty();
        }
        // 与原版一致：中心周围 29 格半径内的群系必须全是海洋神殿要求的深海群系
        int x = center.getBlockX(9);
        int z = center.getBlockZ(9);
        for (Holder<Biome> biome : context.biomeSource().getBiomesWithin(
            x, context.chunkGenerator().getSeaLevel(), z, 29, context.randomState().sampler()
        )) {
            if (!biome.is(BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING)) {
                return Optional.empty();
            }
        }
        // 复用原版神殿的 piece 构建逻辑（createTopPiece 的公开等价实现），
        // 用 onTopOfChunkCenter 把神殿放在 chunk 中心的海底高度（与原版 findGenerationPoint 一致）
        return onTopOfChunkCenter(context, Heightmap.Types.OCEAN_FLOOR_WG, builder -> builder.addPiece(
            new OceanMonumentPieces.MonumentBuilding(
                context.random(),
                center.getMinBlockX() - 29,
                center.getMinBlockZ() - 29,
                Direction.Plane.HORIZONTAL.getRandomDirection(context.random())
            )
        ));
    }

    @Override
    public StructureType<?> type() {
        return TYPE;
    }
}
