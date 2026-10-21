package com.suried.gtoutils;

import com.suried.gtoutils.spatial.LevelStore;

import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(GTOUtils.MOD_ID)
public final class GTOUtils {

    public static final String MOD_ID = "gtoutils";

    public GTOUtils() {
        MinecraftForge.EVENT_BUS.addListener(GTOUtils::onLevelUnload);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) LevelStore.releaseAll(level);
    }
}
