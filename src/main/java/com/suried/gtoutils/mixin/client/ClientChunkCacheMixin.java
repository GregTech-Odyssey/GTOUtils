package com.suried.gtoutils.mixin.client;

import com.suried.gtoutils.spatial.ClientChunkVisibility;

import net.minecraft.client.multiplayer.ClientChunkCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    @Unique
    private Object gtoutils$previousStorage;

    @Inject(method = "updateViewCenter", at = @At("TAIL"))
    private void gtoutils$afterCenter(int x, int z, CallbackInfo ci) {
        ClientChunkVisibility.sync((ClientChunkCache) (Object) this);
    }

    @Inject(method = "updateViewRadius", at = @At("HEAD"))
    private void gtoutils$beforeRadius(int viewDistance, CallbackInfo ci) {
        gtoutils$previousStorage = ClientChunkVisibility.storage((ClientChunkCache) (Object) this);
    }

    @Inject(method = "updateViewRadius", at = @At("TAIL"))
    private void gtoutils$afterRadius(int viewDistance, CallbackInfo ci) {
        Object previous = gtoutils$previousStorage;
        gtoutils$previousStorage = null;
        ClientChunkVisibility.afterResize((ClientChunkCache) (Object) this, previous);
    }
}
