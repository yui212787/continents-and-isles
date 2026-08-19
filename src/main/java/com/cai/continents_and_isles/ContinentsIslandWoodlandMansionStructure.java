package com.cai.continents_and_isles;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionStructure;

import java.util.Optional;

/**
 * 大陆上必定生成的一座林地府邸。
 * <p>
 * 原版 {@link WoodlandMansionStructure} 由 structure_set 的 random_spread 网格随机散布
 * 且只生成于黑森林群系，在超大陆世界里不保证出现。本结构复用原版府邸的完整生成逻辑
 * （Jigsaw 树状 piece 由 super 生成），但把生成位置固定为
 * {@link ContinentIslandField#woodlandMansionChunkPos()}（种子在扇区间隙选定的位置）：
 * <ul>
 *   <li>structure_set 用 spacing=1 / separation=0，保证每个 chunk 都会被判定到</li>
 *   <li>{@link #findGenerationPoint} 只接受"恰好等于固定点 chunk"的候选点，其余返回 empty</li>
 *   <li>固定点周围 128×128 格由群系源强制为黑森林群系，保证府邸有黑森林环境
 *       （biomes 标签 #minecraft:has_structure/woodland_mansion 必然通过）</li>
 * </ul>
 * 因此超大陆中必定且仅生成一处林地府邸。
 */
public class ContinentsIslandWoodlandMansionStructure extends WoodlandMansionStructure {

    public static final MapCodec<ContinentsIslandWoodlandMansionStructure> CODEC =
        simpleCodec(ContinentsIslandWoodlandMansionStructure::new);

    public static final StructureType<ContinentsIslandWoodlandMansionStructure> TYPE = () -> CODEC;

    public ContinentsIslandWoodlandMansionStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        ChunkPos pos = ContinentIslandField.woodlandMansionChunkPos();
        // 只有恰好是固定点 chunk 的候选点才生成（spacing=1 保证每个 chunk 都被判定）
        if (pos == null || !context.chunkPos().equals(pos)) {
            return Optional.empty();
        }
        // 复用原版林地府邸 piece 的完整生成逻辑（Jigsaw 树状结构）
        return super.findGenerationPoint(context);
    }

    @Override
    public StructureType<?> type() {
        return TYPE;
    }
}
