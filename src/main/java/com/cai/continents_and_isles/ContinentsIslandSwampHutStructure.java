package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutStructure;

import java.util.Optional;

/**
 * 群系湖中的定点女巫小屋。
 * <p>
 * 原版 {@link SwampHutStructure} 由 structure_set 的 random_spread 网格决定候选位置，
 * 在超大陆世界里不保证在沼泽湖出现。本结构复用原版女巫小屋的生成逻辑
 * （{@link SwampHutPiece}），把生成位置固定为群系湖内由种子哈希决定的随机点
 * （{@link ContinentIslandField#swampHutChunkPos()}，随机角度 + 15%~85% 半径，不固定在湖心）：
 * <ul>
 *   <li>structure_set 用 spacing=1 / separation=0，保证每个 chunk 都会被判定到</li>
 *   <li>{@link #findGenerationPoint} 只接受"恰好等于该随机位置 chunk"的候选点，其余返回 empty</li>
 *   <li>仅当群系湖选定群系为沼泽（{@link ContinentIslandField#isSwampBiomeLake()}）时有效</li>
 * </ul>
 * 因此沼泽湖中必定且仅生成一处女巫小屋。
 */
public class ContinentsIslandSwampHutStructure extends SwampHutStructure {

    public static final MapCodec<ContinentsIslandSwampHutStructure> CODEC =
        simpleCodec(ContinentsIslandSwampHutStructure::new);

    public static final StructureType<ContinentsIslandSwampHutStructure> TYPE = () -> CODEC;

    public ContinentsIslandSwampHutStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos center = ContinentIslandField.swampHutChunkPos();
        // 只有恰好是群系湖中心 chunk 的候选点才生成（spacing=1 保证每个 chunk 都被判定）
        if (center == null || !context.chunkPos().equals(center)) {
            return Optional.empty();
        }
        // 复用原版女巫小屋 piece：放在 chunk 中心的水面高度（与原版 findGenerationPoint 一致）
        return onTopOfChunkCenter(context, Heightmap.Types.WORLD_SURFACE_WG, builder -> builder.addPiece(
            new SwampHutPiece(context.random(), center.getMinBlockX(), center.getMinBlockZ())
        ));
    }

    @Override
    public StructureType<?> type() {
        return TYPE;
    }
}
