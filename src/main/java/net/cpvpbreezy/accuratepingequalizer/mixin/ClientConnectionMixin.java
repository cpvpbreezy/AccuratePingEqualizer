package net.cpvpbreezy.accuratepingequalizer.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.cpvpbreezy.accuratepingequalizer.EqualizerState;
import net.cpvpbreezy.accuratepingequalizer.bridge.EqualizerConnectionBridge;
import net.cpvpbreezy.accuratepingequalizer.net.EqualizerChannelHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.state.NetworkState;
import net.minecraft.text.Text;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin implements EqualizerConnectionBridge {

    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private NetworkSide side;

    @Unique
    private EqualizerChannelHandler ape$handler;

    @Unique
    private boolean ape$inPlay = false;

    @Unique
    private boolean ape$reconfiguring = false;

    @Unique
    private NetworkPhase ape$lastPhase = null;

    @Unique
    private boolean ape$isClientConnection() {
        return this.side == NetworkSide.CLIENTBOUND;
    }

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void ape$onDisconnect(Text reason, CallbackInfo ci) {
        if (!ape$isClientConnection() || !ape$inPlay) {
            return;
        }
        ape$inPlay = false;
        EqualizerState.get().resetForNewSession();
        if (ape$handler != null) {
            ape$handler.setActive(false);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void ape$onTick(CallbackInfo ci) {
        if (!ape$isClientConnection() || !ape$inPlay || channel == null || !channel.isOpen()) {
            return;
        }
        // Another mod or a proxy rebuilt the pipeline: put ourselves back right away.
        if (channel.pipeline().get(EqualizerChannelHandler.HANDLER_NAME) == null) {
            if (ape$ensureHandler(channel.pipeline())) {
                ape$handler.setActive(true);
                org.slf4j.LoggerFactory.getLogger("AccuratePingEqualizer")
                        .info("Reinstalled channel handler after it was removed");
            }
        }
    }

    @Inject(method = "transitionInbound", at = @At("HEAD"))
    private void ape$onTransitionInbound(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
        if (ape$isClientConnection()) {
            ape$handlePhase(state.id());
        }
    }

    @Inject(method = "transitionOutbound", at = @At("HEAD"))
    private void ape$onTransitionOutbound(NetworkState<?> state, CallbackInfo ci) {
        if (ape$isClientConnection()) {
            ape$handlePhase(state.id());
        }
    }

    @Unique
    private void ape$handlePhase(NetworkPhase phase) {
        if (phase == NetworkPhase.PLAY) {
            ape$enterPlay(!ape$reconfiguring);
            ape$reconfiguring = false;
        } else {
            if (phase == NetworkPhase.CONFIGURATION && ape$lastPhase == NetworkPhase.PLAY) {
                // Proxy server switch / reconfiguration. The client JOIN event resets the
                // equalizer for the new backend (it may not allow it, and its ping differs).
                ape$reconfiguring = true;
            }
            ape$leavePlay();
        }
        ape$lastPhase = phase;
    }

    @Override
    public void ape$signalPlayPhaseEntry() {
        if (ape$isClientConnection() && !ape$inPlay) {
            ape$enterPlay(true);
        }
    }

    @Unique
    private void ape$enterPlay(boolean newSession) {
        if (newSession && !ape$inPlay) {
            EqualizerState.get().resetForNewSession();
        }
        ape$inPlay = true;
        if (channel != null) {
            ape$ensureHandler(channel.pipeline());
        }
        if (ape$handler != null) {
            ape$handler.setActive(true);
        }
    }

    @Unique
    private void ape$leavePlay() {
        if (ape$handler != null) {
            ape$handler.setActive(false);
        }
        if (channel != null && channel.pipeline().get(EqualizerChannelHandler.HANDLER_NAME) != null) {
            channel.pipeline().remove(EqualizerChannelHandler.HANDLER_NAME);
        }
        ape$handler = null;
        ape$inPlay = false;
    }

    @Unique
    private boolean ape$ensureHandler(ChannelPipeline pipeline) {
        if (pipeline == null) {
            return false;
        }
        ChannelHandler existing = pipeline.get(EqualizerChannelHandler.HANDLER_NAME);
        if (existing instanceof EqualizerChannelHandler h) {
            ape$handler = h;
            return false;
        }
        ape$handler = new EqualizerChannelHandler();
        ChannelHandlerContext self = pipeline.context((ChannelHandler) (Object) this);
        String anchor = self != null ? self.name() : (pipeline.get("packet_handler") != null ? "packet_handler" : null);
        if (anchor != null) {
            pipeline.addBefore(anchor, EqualizerChannelHandler.HANDLER_NAME, ape$handler);
        } else {
            pipeline.addLast(EqualizerChannelHandler.HANDLER_NAME, ape$handler);
        }
        return true;
    }
}
