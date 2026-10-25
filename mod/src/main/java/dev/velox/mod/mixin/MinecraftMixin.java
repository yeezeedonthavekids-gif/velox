package dev.velox.mod.mixin;

import dev.velox.mod.FrameClock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The one hook Velox needs: the start of every frame. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void velox$frameStart(CallbackInfo ci) {
        FrameClock.onFrameStart();
    }
}
