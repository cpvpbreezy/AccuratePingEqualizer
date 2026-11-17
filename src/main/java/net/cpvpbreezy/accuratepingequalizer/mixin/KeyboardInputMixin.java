package net.cpvpbreezy.accuratepingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.cpvpbreezy.accuratepingequalizer.InputDelay;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;

/** Input delay for key presses and releases (movement, hotbar, offhand swap, drop, inventory keys...). */
@Mixin(Keyboard.class)
public abstract class KeyboardInputMixin {

    @Shadow
    private void onKey(long window, int action, KeyInput input) {}

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void ape$delayKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (InputDelay.capture(() -> onKey(window, action, input))) {
            ci.cancel();
        }
    }
}
