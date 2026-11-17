package net.cpvpbreezy.accuratepingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.cpvpbreezy.accuratepingequalizer.bridge.EqualizerConnectionBridge;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void ape$onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        if (((ClientPlayNetworkHandler) (Object) this).getConnection() instanceof EqualizerConnectionBridge bridge) {
            bridge.ape$signalPlayPhaseEntry();
        }
    }
}
