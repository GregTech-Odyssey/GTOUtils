package com.suried.gtoutils.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * 把维度的方块状态按区段交给 {@link SectionScan}。功能：
 * <ul>
 * <li>读到的值与逐格调用 {@code Level.getBlockState} 相同，未加载的区块也和它一样按需取：高度范围外与空区块整段是虚空空气，全空气区段整段是空气，
 * 其余区段用调色板判断能否跳过，调试世界退回逐格读取</li>
 * <li>{@link #betweenClosed}/{@link #inRange}/{@link #withinManhattan}：对某维度直接做 {@link SectionScan} 的三种扫描，betweenClosed 的角点顺序随意，
 * 同 {@code BlockPos.betweenClosed(a, b)}</li>
 * </ul>
 */
public final class LevelSections implements SectionScan.Sections<BlockState> {

    private final Level level;
    private final boolean debug;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int chunkX, chunkZ;
    private @Nullable LevelChunk chunk;
    private @Nullable LevelChunkSection section;
    private int baseX, baseY, baseZ;

    public LevelSections(Level level) {
        this.level = level;
        this.debug = level.isDebug();
    }

    public static int betweenClosed(Level level, BlockPos a, BlockPos b, Predicate<? super BlockState> match, int limit, Collection<? super BlockPos> out) {
        return betweenClosed(level, a, b, match, (state, pos) -> true, limit, out);
    }

    public static int betweenClosed(Level level, BlockPos a, BlockPos b, Predicate<? super BlockState> palette,
                                    SectionScan.CellTest<? super BlockState> test, int limit, Collection<? super BlockPos> out) {
        return SectionScan.betweenClosed(new LevelSections(level), Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()), palette, test, limit, out);
    }

    public static int inRange(Level level, BlockPos center, BlockRange range, Predicate<? super BlockState> match, int limit, Collection<? super BlockPos> out) {
        return inRange(level, center, range, match, (state, pos) -> true, limit, out);
    }

    public static int inRange(Level level, BlockPos center, BlockRange range, Predicate<? super BlockState> palette,
                              SectionScan.CellTest<? super BlockState> test, int limit, Collection<? super BlockPos> out) {
        return SectionScan.inRange(new LevelSections(level), center.getX(), center.getY(), center.getZ(), range, palette, test, limit, out);
    }

    public static int withinManhattan(Level level, BlockPos center, int rx, int ry, int rz, Predicate<? super BlockState> match, int limit,
                                      Collection<? super BlockPos> out) {
        return withinManhattan(level, center, rx, ry, rz, match, (state, pos) -> true, limit, out);
    }

    public static int withinManhattan(Level level, BlockPos center, int rx, int ry, int rz, Predicate<? super BlockState> palette,
                                      SectionScan.CellTest<? super BlockState> test, int limit, Collection<? super BlockPos> out) {
        return SectionScan.withinManhattan(new LevelSections(level), center.getX(), center.getY(), center.getZ(), rx, ry, rz, palette, test, limit, out);
    }

    @Override
    public @Nullable BlockState seek(int sectionX, int sectionY, int sectionZ) {
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) return Blocks.VOID_AIR.defaultBlockState();
        if (chunk == null || chunkX != sectionX || chunkZ != sectionZ) {
            chunk = level.getChunk(sectionX, sectionZ);
            chunkX = sectionX;
            chunkZ = sectionZ;
        }
        if (chunk instanceof EmptyLevelChunk) return Blocks.VOID_AIR.defaultBlockState();
        baseX = sectionX << 4;
        baseY = sectionY << 4;
        baseZ = sectionZ << 4;
        if (debug) {
            section = null;
            return null;
        }
        LevelChunkSection s = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
        if (s.hasOnlyAir()) return Blocks.AIR.defaultBlockState();
        section = s;
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean mayContain(Predicate<? super BlockState> palette) {
        return section == null || section.maybeHas((Predicate<BlockState>) palette);
    }

    @Override
    public BlockState get(int localX, int localY, int localZ) {
        if (section == null) return level.getBlockState(cursor.set(baseX + localX, baseY + localY, baseZ + localZ));
        return section.getBlockState(localX, localY, localZ);
    }
}
