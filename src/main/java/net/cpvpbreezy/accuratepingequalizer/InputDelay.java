package net.cpvpbreezy.accuratepingequalizer;

import java.util.ArrayDeque;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Input delay: your mouse clicks, scrolls and key presses/releases are held back before the game reacts
 * to them at all, then replayed in exactly the same order. Because this happens before the game acts,
 * everything you do (hits and the knockback you deal, crystals, anchors, blocks, hotbar and offhand
 * swaps, inventory clicks, movement keys) happens later on your screen AND reaches the server later,
 * while the network delay is untouched, so the server still measures exactly your target ping.
 *
 * <p>Mouse aim is never delayed (real ping doesn't delay your crosshair), and neither is typing in chat
 * or clicking through menus; only the game world and inventory/container screens are affected.</p>
 */
public final class InputDelay {

    /** -1 = auto (the outgoing half of the added ping), 0 = off, >0 = fixed milliseconds. */
    private static int setting = parse(ApeConfig.get("inputDelay", "auto"));
    public static final int MAX_MS = 500;

    private record Pending(long dueNs, Runnable replay) {}

    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    private static boolean replaying = false;

    private InputDelay() {}

    private static int parse(String v) {
        if (v.equalsIgnoreCase("auto")) return -1;
        if (v.equalsIgnoreCase("off")) return 0;
        try {
            return Math.max(0, Math.min(MAX_MS, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void setAuto() {
        setting = -1;
        ApeConfig.set("inputDelay", "auto");
    }

    public static void setOff() {
        setting = 0;
        ApeConfig.set("inputDelay", "off");
    }

    public static void setFixed(int ms) {
        setting = Math.max(0, Math.min(MAX_MS, ms));
        ApeConfig.set("inputDelay", setting == 0 ? "off" : Integer.toString(setting));
    }

    public static boolean isAuto() {
        return setting < 0;
    }

    /** Current input delay in ms (0 when the equalizer is off). */
    public static double currentMs() {
        EqualizerState st = EqualizerState.get();
        if (!st.isActive() || setting == 0) {
            return 0;
        }
        if (setting > 0) {
            return setting;
        }
        // auto: the outgoing half of the ping being added, i.e. how long a real connection would
        // take to carry your action to the server on top of the ping you really have.
        return st.getCurrentDelayMs() / 2.0;
    }

    /** Called from the input mixins (render thread). True = this event was taken and will be replayed later. */
    public static boolean capture(Runnable replay) {
        if (replaying) {
            return false;
        }
        double ms = currentMs();
        if (ms <= 0) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null && !(mc.currentScreen instanceof HandledScreen<?>)) {
            return false; // menus and chat stay instant
        }
        long due = System.nanoTime() + (long) (ms * 1_000_000.0);
        Pending last = QUEUE.peekLast();
        if (last != null && last.dueNs() > due) {
            due = last.dueNs(); // never reorder inputs, even while the delay is changing
        }
        QUEUE.addLast(new Pending(due, replay));
        return true;
    }

    /** Called every frame and every tick on the render thread: replays inputs whose time has come. */
    public static void process() {
        if (QUEUE.isEmpty()) {
            return;
        }
        boolean flush = currentMs() <= 0; // equalizer or input delay turned off: release everything now
        long now = System.nanoTime();
        while (!QUEUE.isEmpty() && (flush || QUEUE.peekFirst().dueNs() <= now)) {
            Pending p = QUEUE.pollFirst();
            replaying = true;
            try {
                p.replay().run();
            } finally {
                replaying = false;
            }
        }
    }

    public static String describe() {
        if (setting == 0) {
            return "off";
        }
        String base = setting > 0 ? setting + "ms" : "auto";
        double now = currentMs();
        return now > 0 ? base + String.format(" (%.0fms now)", now) : base;
    }
}
