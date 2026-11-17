package net.cpvpbreezy.accuratepingequalizer.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.cpvpbreezy.accuratepingequalizer.EqualizerState;
import net.cpvpbreezy.accuratepingequalizer.OriginalModInterop;
import net.cpvpbreezy.accuratepingequalizer.net.PingEqualizerControlPayload;
import net.cpvpbreezy.accuratepingequalizer.net.PingEqualizerStateQueryPayload;
import net.cpvpbreezy.accuratepingequalizer.net.PingEqualizerStateResponsePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.text.Text;

public class AccuratePingEqualizerClient implements ClientModInitializer {

    /** Kept identical to the original mod so servers that watch for it still recognise it. */
    private static final String ANNOUNCE_PREFIX = "[Ping Equalizer] ";

    private String lastAnnouncement = "";
    private boolean followOriginalServerSwitch = false;
    private boolean originalWasActive = false;

    @Override
    public void onInitializeClient() {
        if (OriginalModInterop.isLoaded()) {
            // Ravenclaw's Ping Equalizer is installed too. It registers the same server channels, and
            // registering them twice crashes the game, so we let it own them and mirror its server switch.
            followOriginalServerSwitch = true;
        } else {
            registerServerChannels();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            coordinateWithOriginal();
            EqualizerState.get().tick(client);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            lastAnnouncement = "";
            EqualizerState.get().resetForNewSession();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            lastAnnouncement = "";
            EqualizerState.get().resetForNewSession();
        });

        registerCommands();
    }

    private void coordinateWithOriginal() {
        if (!OriginalModInterop.isLoaded()) {
            return;
        }
        EqualizerState state = EqualizerState.get();
        if (followOriginalServerSwitch) {
            boolean enabled = OriginalModInterop.originalServerEnabled();
            if (enabled != state.isServerEnabled()) {
                state.setServerEnabled(enabled);
                if (!enabled) {
                    sendLocal("§cAccurate Ping Equalizer disabled by server.");
                }
            }
        }
        boolean originalActive = OriginalModInterop.isOriginalActive();
        if (originalActive && !originalWasActive && !state.isOffMode()) {
            // The user just turned the original on: it wins, so the two delays never stack.
            state.setOff();
            sendLocal("§eAccurate Ping Equalizer turned off because Ravenclaw's Ping Equalizer was turned on (they would stack).");
        }
        originalWasActive = originalActive;
    }

    private void stopOriginalIfRunning() {
        if (OriginalModInterop.turnOriginalOff()) {
            originalWasActive = false;
            sendLocal("§eTurned off Ravenclaw's Ping Equalizer so the delays don't stack.");
        }
    }

    private void registerServerChannels() {
        PayloadTypeRegistry.playS2C().register(PingEqualizerControlPayload.ID, PingEqualizerControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PingEqualizerStateQueryPayload.ID, PingEqualizerStateQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PingEqualizerStateResponsePayload.ID, PingEqualizerStateResponsePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(PingEqualizerControlPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    EqualizerState state = EqualizerState.get();
                    boolean was = state.isServerEnabled();
                    state.setServerEnabled(payload.enabled());
                    if (was != payload.enabled()) {
                        sendLocal(payload.enabled()
                                ? "Accurate Ping Equalizer enabled by server."
                                : "§cAccurate Ping Equalizer disabled by server.");
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(PingEqualizerStateQueryPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    EqualizerState state = EqualizerState.get();
                    ClientPlayNetworking.send(new PingEqualizerStateResponsePayload(
                            payload.requestId(),
                            state.isServerEnabled(),
                            state.getMode().name().toLowerCase(),
                            state.getCurrentDelayMs(),
                            state.getBasePing(),
                            state.getTotalPing()));
                }));
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            int max = EqualizerState.MAX_ADDED_PING_MS;
            LiteralCommandNode<FabricClientCommandSource> root = dispatcher.register(
                    ClientCommandManager.literal("accuratepingequalizer")
                            .then(ClientCommandManager.literal("add")
                                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0, max))
                                            .executes(ctx -> runAdd(ctx))))
                            .then(ClientCommandManager.literal("total")
                                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0, 2000))
                                            .executes(ctx -> runTotal(ctx))))
                            .then(ClientCommandManager.literal("status")
                                    .executes(ctx -> {
                                        sendLocal(EqualizerState.get().getStatusMessage());
                                        return 1;
                                    }))
                            .then(ClientCommandManager.literal("off")
                                    .executes(ctx -> runOff()))
                            .then(ClientCommandManager.literal("jitter")
                                    .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(0, EqualizerState.MAX_JITTER_MS))
                                            .executes(ctx -> runJitter(IntegerArgumentType.getInteger(ctx, "ms")))))
                            .then(ClientCommandManager.literal("capture")
                                    .executes(ctx -> runCapture()))
                            .then(ClientCommandManager.literal("match")
                                    .executes(ctx -> runMatch())));
            // /pe and /pingequalizer are left to Ravenclaw's Ping Equalizer so both mods can be installed.
            dispatcher.register(ClientCommandManager.literal("ape").redirect(root));
        });
    }

    private int runAdd(CommandContext<FabricClientCommandSource> ctx) {
        if (!canChange()) {
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        EqualizerState state = EqualizerState.get();
        if (amount == 0) {
            return runOff();
        }
        if (state.isAddingDelay(amount)) {
            sendLocal("Accurate Ping Equalizer already adding " + amount + "ms delay.");
            return 1;
        }
        stopOriginalIfRunning();
        state.setAddPing(amount);
        announce("Added " + amount + "ms delay.");
        return 1;
    }

    private int runTotal(CommandContext<FabricClientCommandSource> ctx) {
        if (!canChange()) {
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        EqualizerState state = EqualizerState.get();
        if (amount == 0) {
            return runOff();
        }
        if (state.isTotalPingTarget(amount)) {
            sendLocal("Accurate Ping Equalizer already targeting " + amount + "ms total ping.");
            return 1;
        }
        stopOriginalIfRunning();
        state.setTotalPing(amount);
        announce("Total ping set to " + amount + "ms.");
        return 1;
    }

    private int runOff() {
        if (!canChange()) {
            return 0;
        }
        EqualizerState state = EqualizerState.get();
        if (state.isOffMode()) {
            sendLocal("Accurate Ping Equalizer already disabled.");
            return 1;
        }
        state.setOff();
        announce("Disabled.");
        return 1;
    }

    private int runJitter(int ms) {
        if (!canChange()) {
            return 0;
        }
        EqualizerState.get().setJitter(ms);
        if (ms == 0) {
            announce("Wobble off (perfectly steady ping).");
        } else {
            announce("Wobble set to ±" + ms + "ms.");
        }
        return 1;
    }

    private int runCapture() {
        EqualizerState state = EqualizerState.get();
        if (state.isCapturing()) {
            sendLocal("Already measuring this server, hold on...");
            return 1;
        }
        sendLocal("Measuring this server's real ping and wobble for 10 seconds...");
        state.startCapture(() -> MinecraftClient.getInstance().execute(() -> {
            double ping = state.getProfilePing();
            if (ping < 0) {
                sendLocal("§cCould not measure this server (no replies). Try again.");
                return;
            }
            sendLocal(String.format("§aCaptured: %.0fms ping, ±%.1fms wobble. Use /ape match on another server to play exactly like this.",
                    ping, state.getProfileJitter()));
        }));
        return 1;
    }

    private int runMatch() {
        if (!canChange()) {
            return 0;
        }
        EqualizerState state = EqualizerState.get();
        double ping = state.getProfilePing();
        if (ping < 0) {
            sendLocal("§cNo captured profile yet. Join the server you want to copy and run /ape capture first.");
            return 0;
        }
        int target = (int) Math.round(ping);
        int jitter = (int) Math.round(state.getProfileJitter());
        stopOriginalIfRunning();
        state.setJitter(jitter);
        state.setTotalPing(target);
        announce("Matching captured connection: " + target + "ms total, ±" + jitter + "ms wobble.");
        return 1;
    }

    private boolean canChange() {
        if (!EqualizerState.get().isServerEnabled()) {
            sendLocal("§cAccurate Ping Equalizer is disabled by this server.");
            return false;
        }
        if (isChatCommandsOnly()) {
            sendLocal("§cChat is set to commands-only; ping changes must be announced publicly; command blocked.");
            return false;
        }
        return true;
    }

    private void announce(String message) {
        if (message.equals(lastAnnouncement)) {
            return;
        }
        lastAnnouncement = message;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatMessage(ANNOUNCE_PREFIX + message);
        }
    }

    private static void sendLocal(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private static boolean isChatCommandsOnly() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.options != null && mc.options.getChatVisibility().getValue() == ChatVisibility.SYSTEM;
    }
}
