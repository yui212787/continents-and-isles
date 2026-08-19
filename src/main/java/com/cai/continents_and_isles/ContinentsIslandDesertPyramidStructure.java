package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidStructure;

import java.util.Optional;

/**
 * 沙漠扇区中必定生成的一座沙漠神庙。
 * <p>
 * 原版 {@link DesertPyramidStructure} 由 structure_set 的 random_spread 网格随机散布，
 * 在超大陆世界里不保证出现在沙漠扇区。本结构复用原版神庙的全部生成逻辑
 * （{@link DesertPyramidPiece} 由 super 生成），但把生成位置固定为
 * {@link ContinentIslandField#desertPyramidChunkPos()}（沙漠扇区环带内种子决定的位置）：
 * <ul>
 *   <li>structure_set 用 spacing=1 / separation=0，保证每个 chunk 都会被判定到</li>
 *   <li>{@link #findGenerationPoint} 只接受"恰好等于沙漠扇区固定点 chunk"的候选点，其余返回 empty</li>
 *   <li>群系条件由 structure JSON 的 biomes 标签（#minecraft:has_structure/desert_pyramid）保证，
 *       固定点落在沙漠/恶地群系上，必然通过</li>
 * </ul>
 * 因此超大陆的沙漠扇区中必定且仅生成一处沙漠神庙。
 */
public class ContinentsIslandDesertPyramidStructure extends DesertPyramidStructure {

    public static final MapCodec<ContinentsIslandDesertPyramidStructure> CODEC =
        simpleCodec(ContinentsIslandDesertPyramidStructure::new);

    public static final StructureType<ContinentsIslandDesertPyramidStructure> TYPE = () -> CODEC;

    public ContinentsIslandDesertPyramidStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos pos = ContinentIslandField.desertPyramidChunkPos();
        // 只有恰好是沙漠扇区固定点 chunk 的候选点才生成（spacing=1 保证每个 chunk 都被判定）
        if (pos == null || !context.chunkPos().equals(pos)) {
            return Optional.empty();
        }
        // 复用原版沙漠神庙 piece 的完整生成逻辑
        return super.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return TYPE;
    }
}
