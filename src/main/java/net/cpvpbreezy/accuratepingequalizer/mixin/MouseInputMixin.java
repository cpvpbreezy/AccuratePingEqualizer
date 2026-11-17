package net.cpvpbreezy.accuratepingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.cpvpbreezy.accuratepingequalizer.InputDelay;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;

/** Input delay for mouse buttons and the scroll wheel (aim is never delayed). */
@Mixin(Mouse.class)
public abstract class MouseInputMixin {

    @Shadow
    private void onMouseButton(long window, MouseInput input, int action) {}

    @Shadow
    private void onMouseScroll(long window, double horizontal, double vertical) {}

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void ape$delayMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        if (InputDelay.capture(() -> onMouseButton(window, input, action))) {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void ape$delayMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (InputDelay.capture(() -> onMouseScroll(window, horizontal, vertical))) {
            ci.cancel();
        }
    }
}
