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
 * 女巫小屋保底结构：在湿地带与沼泽群系湖中各保底生成一处（至少一个，非"只能一个"）。
 * <p>
 * 原版 {@link SwampHutStructure} 由 structure_set 的 random_spread 网格决定候选位置，
 * 在超大陆世界里不保证出现。本结构复用原版女巫小屋的生成逻辑（{@link SwampHutPiece}），
 * 把生成位置固定为两个种子决定的保底点，任一命中即生成：
 * <ul>
 *   <li>湿地带保底点 {@link ContinentIslandField#wetlandSwampHutChunkPos()}：
 *       群岛-环山带过渡湿地（0.80R~0.98R）内由种子选定，群系必为沼泽/红树林</li>
 *   <li>群系湖保底点 {@link ContinentIslandField#swampHutChunkPos()}：
 *       仅当群系湖（湖2）选定群系为沼泽（{@link ContinentIslandField#isSwampBiomeLake()}）时有效，
 *       位置在湖内由种子哈希决定（随机角度 + 15%~85% 半径，不固定在湖心）</li>
 * </ul>
 * 实现要点：
 * <ul>
 *   <li>structure_set 用 spacing=1 / separation=0，保证每个 chunk 都会被判定到</li>
 *   <li>{@link #findGenerationPoint} 只接受"恰好等于任一保底点 chunk"的候选点，其余返回 empty</li>
 *   <li>湿地带保底点必定有效；群系湖保底点按种子决定（湖2 为沼泽时与湿地带保底点可同时存在，
 *       即超大陆内最多两个保底女巫小屋）</li>
 * </ul>
 * 除保底点外，原版 swamp_huts 结构集未在本模组中被覆盖，仍可在其他沼泽群系中按原版规则生成，
 * 因此女巫小屋总数不受保底限制。
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
        ChunkPos pos = context.chunkPos();
        // 两个保底点：湿地带（必定）与沼泽群系湖（湖2 为沼泽时）
        ChunkPos wetland = ContinentIslandField.wetlandSwampHutChunkPos();
        ChunkPos lake = ContinentIslandField.swampHutChunkPos();
        if ((wetland == null || !pos.equals(wetland)) && (lake == null || !pos.equals(lake))) {
            return Optional.empty();
        }
        ChunkPos center = (wetland != null && pos.equals(wetland)) ? wetland : lake;
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
