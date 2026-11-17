package net.cpvpbreezy.accuratepingequalizer.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.cpvpbreezy.accuratepingequalizer.EqualizerState;
import net.cpvpbreezy.accuratepingequalizer.OptimizerPause;
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
import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
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
            OptimizerPause.tick(EqualizerState.get().isActive());
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
                            .then(ClientCommandManager.literal("match")
                                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                MinecraftClient mc = MinecraftClient.getInstance();
                                                if (mc.getNetworkHandler() != null) {
                                                    String self = mc.player == null ? "" : mc.player.getGameProfile().name();
                                                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                                    for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
                                                        String n = e.getProfile().name();
                                                        if (!n.equalsIgnoreCase(self) && n.toLowerCase(Locale.ROOT).startsWith(typed)) {
                                                            builder.suggest(n);
                                                        }
                                                    }
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> runMatch(StringArgumentType.getString(ctx, "player"))))));
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

    private int runMatch(String name) {
        if (!canChange()) {
            return 0;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) {
            return 0;
        }
        if (mc.player != null && mc.player.getGameProfile().name().equalsIgnoreCase(name)) {
            sendLocal("\u00A7cYou can't match your own ping.");
            return 0;
        }
        PlayerListEntry target = null;
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            if (e.getProfile().name().equalsIgnoreCase(name)) {
                target = e;
                break;
            }
        }
        if (target == null) {
            sendLocal("\u00A7cNo player named " + name + " is on this server.");
            return 0;
        }
        int theirs = target.getLatency();
        if (theirs <= 0) {
            sendLocal("\u00A7cThe server hasn't reported " + target.getProfile().name() + "'s ping yet. Try again in a few seconds.");
            return 0;
        }
        stopOriginalIfRunning();
        EqualizerState.get().setMatchPlayer(target.getProfile().id(), target.getProfile().name(), theirs);
        announce("Matching " + target.getProfile().name() + "'s ping (" + theirs + "ms).");
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
