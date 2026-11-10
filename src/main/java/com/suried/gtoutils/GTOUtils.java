package com.suried.gtoutils;

import com.suried.gtoutils.level.LevelStore;
import com.suried.gtoutils.schedule.Schedulers;
import com.suried.gtoutils.schedule.client.ClientSchedulerEvents;

import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(GTOUtils.MOD_ID)
public final class GTOUtils {

    public static final String MOD_ID = "gtoutils";

    public GTOUtils() {
        MinecraftForge.EVENT_BUS.addListener(GTOUtils::onLevelUnload);
        Schedulers.register();
        if (FMLEnvironment.dist.isClient()) ClientSchedulerEvents.register();
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) LevelStore.releaseAll(level);
    }
}
