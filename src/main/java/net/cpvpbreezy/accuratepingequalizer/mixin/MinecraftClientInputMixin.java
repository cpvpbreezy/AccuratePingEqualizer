package net.cpvpbreezy.accuratepingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.cpvpbreezy.accuratepingequalizer.InputDelay;
import net.minecraft.client.MinecraftClient;

/** Replays delayed inputs as soon as they are due: checked every frame and every tick. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientInputMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void ape$replayInputsFrame(boolean tick, CallbackInfo ci) {
        InputDelay.process();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void ape$replayInputsTick(CallbackInfo ci) {
        InputDelay.process();
    }
}
