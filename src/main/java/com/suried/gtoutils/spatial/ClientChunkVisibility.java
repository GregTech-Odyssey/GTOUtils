package com.suried.gtoutils.spatial;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 客户端区块缓存移动视野时，原版只改视野中心，落到范围外的区块要等槽位被复用才卸载，缩小视野半径时被丢弃的区块不会卸载；
 * 这里让索引跟原版的可见范围一致：范围外的区块里的方块实体先从索引里摘掉，回到范围内再登记。
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class ClientChunkVisibility {

    public interface View {

        AtomicReferenceArray<LevelChunk> gtoutils$chunks();

        boolean gtoutils$inRange(int x, int z);
    }

    private static final Field STORAGE = ObfuscationReflectionHelper.findField(ClientChunkCache.class, "f_104410_");

    private ClientChunkVisibility() {}

    public static Object storage(ClientChunkCache cache) {
        try {
            return STORAGE.get(cache);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void sync(ClientChunkCache cache) {
        sync((View) storage(cache));
    }

    public static void afterResize(ClientChunkCache cache, Object previous) {
        View now = (View) storage(cache);
        if (previous instanceof View old && old != now) {
            Set<LevelChunk> kept = Collections.newSetFromMap(new IdentityHashMap<>());
            AtomicReferenceArray<LevelChunk> current = now.gtoutils$chunks();
            for (int i = 0, n = current.length(); i < n; i++) {
                LevelChunk chunk = current.get(i);
                if (chunk != null) kept.add(chunk);
            }
            AtomicReferenceArray<LevelChunk> dropped = old.gtoutils$chunks();
            for (int i = 0, n = dropped.length(); i < n; i++) {
                LevelChunk chunk = dropped.get(i);
                if (chunk != null && !kept.contains(chunk)) BlockEntityTracking.setVisible(chunk, false);
            }
        }
        sync(now);
    }

    private static void sync(View view) {
        AtomicReferenceArray<LevelChunk> chunks = view.gtoutils$chunks();
        for (int i = 0, n = chunks.length(); i < n; i++) {
            LevelChunk chunk = chunks.get(i);
            if (chunk == null) continue;
            ChunkPos pos = chunk.getPos();
            BlockEntityTracking.setVisible(chunk, view.gtoutils$inRange(pos.x, pos.z));
        }
    }
}
