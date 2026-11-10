package com.suried.gtoutils.schedule.client;

import com.suried.gtoutils.schedule.Schedulers;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public final class ClientSchedulerEvents {

    private ClientSchedulerEvents() {}

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(ClientSchedulerEvents::onLoggingOut);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Schedulers.resetClient();
    }
}
