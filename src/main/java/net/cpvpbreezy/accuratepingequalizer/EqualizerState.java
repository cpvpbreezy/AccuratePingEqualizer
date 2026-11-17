package net.cpvpbreezy.accuratepingequalizer;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.cpvpbreezy.accuratepingequalizer.net.EqualizerChannelHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

/**
 * Holds the equalizer mode and works out how much delay to add.
 *
 * <p>Base ping is measured with dedicated probe packets that skip the delay
 * queues and are timed at the network edge of the pipeline. The estimate is
 * therefore the real network round trip, independent of whatever delay is
 * currently applied, so changing the target never needs re-measurement and
 * the estimate cannot feed back on itself.</p>
 */
public final class EqualizerState {
    public enum Mode { OFF, ADD, TOTAL }

    private static final EqualizerState INSTANCE = new EqualizerState();

    public static final int MAX_ADDED_PING_MS = 1000;

    /** Probe tag marker: our probes carry this in the high bits of startTime so they never collide with vanilla's. */
    public static final long PROBE_TAG = 0x4150_4500_0000_0000L;
    private static final long PROBE_TAG_MASK = 0xFFFF_FF00_0000_0000L;

    private static final long PROBE_INTERVAL_TOTAL_NS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final long PROBE_INTERVAL_ADD_NS = TimeUnit.MILLISECONDS.toNanos(1000);
    private static final long PROBE_INTERVAL_BURST_NS = TimeUnit.MILLISECONDS.toNanos(60);
    private static final int PROBE_BURST_COUNT = 6;
    private static final long PROBE_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long BASE_FRESH_NS = TimeUnit.SECONDS.toNanos(30);

    private static final int WINDOW = 10;
    private static final int SAMPLES_BEFORE_JUMP = 3;
    private static final double STEP_CHANGE_MS = 20.0;
    private static final int STEP_CHANGE_RUN = 3;

    private static final double DEADBAND_MS = 1.0;
    private static final double SLEW_MS_PER_SECOND = 40.0;

    /** How long after the last hit / place / anchor click / damage the delay stays frozen. */
    private static final long COMBAT_LOCK_NS = TimeUnit.SECONDS.toNanos(3);
    /** Even in combat, a change this big means the real connection moved; follow it. */
    private static final double COMBAT_OVERRIDE_MS = 30.0;

    private volatile long combatUntilNs = 0;

    /** /ape match <player>: follow that player's tab-list ping as the total target. */
    private volatile UUID followUuid;
    private volatile String followName;
    private boolean followLost = false;

    private volatile Mode mode = Mode.OFF;
    private volatile boolean serverEnabled = true;
    private int addAmount = 0;
    private int totalTarget = 0;

    /** Total added round-trip delay currently applied, in milliseconds (fractional). */
    private volatile double appliedDelayMs = 0;
    private boolean jumpPending = false;
    private long lastControlNs = 0;

    private volatile EqualizerChannelHandler handler;
    private final AtomicLong probeSeq = new AtomicLong();
    private final Map<Long, Long> probesInFlight = new ConcurrentHashMap<>();
    private long lastProbeNs = 0;
    private int burstRemaining = 0;

    private final double[] samples = new double[WINDOW];
    private int sampleCount = 0;
    private int sampleIndex = 0;
    private int samplesSinceCommand = 0;
    private double baseMs = -1;
    private double jitterMs = 0;
    private long lastSampleNs = 0;

    private EqualizerState() {}

    public static EqualizerState get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ commands

    public synchronized void setOff() {
        followUuid = null;
        followName = null;
        mode = Mode.OFF;
        appliedDelayMs = 0;
        jumpPending = false;
        burstRemaining = 0;
    }

    public synchronized void setAddPing(int amount) {
        if (!serverEnabled) {
            setOff();
            return;
        }
        followUuid = null;
        followName = null;
        mode = Mode.ADD;
        addAmount = clamp(amount);
        appliedDelayMs = addAmount;
        jumpPending = false;
        startBurst();
    }

    public synchronized void setTotalPing(int target) {
        followUuid = null;
        followName = null;
        applyTotal(target);
    }

    /** Matches another player's ping (from the tab list) and keeps following it as it changes. */
    public synchronized void setMatchPlayer(UUID uuid, String name, int theirPing) {
        applyTotal(theirPing);
        if (mode == Mode.TOTAL) {
            followUuid = uuid;
            followName = name;
            followLost = false;
        }
    }

    private void applyTotal(int target) {
        if (!serverEnabled) {
            setOff();
            return;
        }
        Mode previous = mode;
        mode = Mode.TOTAL;
        totalTarget = Math.max(0, target);
        samplesSinceCommand = 0;
        long now = System.nanoTime();
        if (hasFreshBase(now)) {
            // The base is a true network measurement, so the right delay is known immediately.
            appliedDelayMs = clamp(totalTarget - baseMs);
            jumpPending = false;
        } else {
            // No measurement yet: start from the server's tab-list ping (only trustworthy while
            // nothing was being added), then jump to the measured value after a few probes.
            int tab = previous == Mode.OFF ? tabLatency() : -1;
            appliedDelayMs = tab > 0 ? clamp(totalTarget - tab) : 0;
            jumpPending = true;
        }
        startBurst();
    }

    public synchronized void setServerEnabled(boolean enabled) {
        serverEnabled = enabled;
        if (!enabled) {
            setOff();
        }
    }

    public synchronized void resetForNewSession() {
        serverEnabled = true;
        setOff();
        clearMeasurements();
    }

    // ------------------------------------------------------------------ pipeline hooks

    public void attachHandler(EqualizerChannelHandler h) {
        this.handler = h;
    }

    public void detachHandler(EqualizerChannelHandler h) {
        if (this.handler == h) {
            this.handler = null;
        }
        probesInFlight.clear();
    }

    public static boolean isProbe(long startTime) {
        return (startTime & PROBE_TAG_MASK) == PROBE_TAG;
    }

    /** Called on the network thread right before a probe is written to the wire. */
    public void onProbeSent(long tag, long nanoTime) {
        probesInFlight.put(tag, nanoTime);
    }

    /** Called on the network thread as soon as a probe reply comes off the wire. */
    public void onProbeReply(long tag, long nanoTime) {
        Long sent = probesInFlight.remove(tag);
        if (sent == null) {
            return;
        }
        addSample((nanoTime - sent) / 1_000_000.0, nanoTime);
    }

    /** Called from the network thread whenever a combat packet goes out or combat feedback comes in. */
    public void markCombat(long nanoTime) {
        combatUntilNs = nanoTime + COMBAT_LOCK_NS;
    }

    public boolean isInCombat(long nanoTime) {
        return nanoTime - combatUntilNs < 0;
    }

    public boolean isActive() {
        return serverEnabled && mode != Mode.OFF;
    }

    /** Outbound half of the added delay, in nanoseconds. */
    public long outboundDelayNanos() {
        if (!isActive()) {
            return 0;
        }
        return (long) (appliedDelayMs * 500_000.0);
    }

    /** Inbound half of the added delay, in nanoseconds. */
    public long inboundDelayNanos() {
        if (!isActive()) {
            return 0;
        }
        return (long) (appliedDelayMs * 1_000_000.0) - outboundDelayNanos();
    }

    // ------------------------------------------------------------------ per tick

    public void tick(MinecraftClient client) {
        long now = System.nanoTime();
        expireProbes(now);
        if (!isActive()) {
            lastControlNs = now;
            return;
        }
        sendProbeIfDue(now);
        followMatchedPlayer(client);
        synchronized (this) {
            double dtSeconds = lastControlNs == 0 ? 0.05 : Math.min(0.25, (now - lastControlNs) / 1e9);
            lastControlNs = now;
            if (mode == Mode.ADD) {
                appliedDelayMs = addAmount;
                return;
            }
            if (baseMs < 0) {
                return;
            }
            double desired = clamp(totalTarget - baseMs);
            if (jumpPending) {
                if (samplesSinceCommand >= SAMPLES_BEFORE_JUMP) {
                    appliedDelayMs = desired;
                    jumpPending = false;
                }
                return;
            }
            double diff = desired - appliedDelayMs;
            if (Math.abs(diff) <= DEADBAND_MS) {
                return;
            }
            if (isInCombat(now) && Math.abs(diff) < COMBAT_OVERRIDE_MS) {
                // Combat lock: keep the delay perfectly constant mid-fight so every hit, crystal and
                // anchor click reaches the server with the same timing (and the same spacing between
                // them) instead of drifting as the base estimate wobbles.
                return;
            }
            // Follow drift in the real ping smoothly so packets are never bunched up or stalled.
            double step = Math.min(Math.abs(diff), SLEW_MS_PER_SECOND * dtSeconds);
            appliedDelayMs += Math.copySign(step, diff);
        }
    }

    /** Keeps the target on the matched player's current tab-list ping. */
    private void followMatchedPlayer(MinecraftClient client) {
        UUID uuid = followUuid;
        if (uuid == null || client == null) {
            return;
        }
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        PlayerListEntry entry = nh == null ? null : nh.getPlayerListEntry(uuid);
        synchronized (this) {
            if (entry == null) {
                // They left: keep their last ping.
                followLost = true;
                return;
            }
            followLost = false;
            int theirs = entry.getLatency();
            if (theirs > 0 && theirs != totalTarget) {
                // The tick's slew (and combat lock) moves the delay there smoothly.
                totalTarget = theirs;
            }
        }
    }

    private void sendProbeIfDue(long now) {
        EqualizerChannelHandler h = handler;
        if (h == null) {
            return;
        }
        long interval;
        if (burstRemaining > 0) {
            interval = PROBE_INTERVAL_BURST_NS;
        } else {
            interval = mode == Mode.TOTAL ? PROBE_INTERVAL_TOTAL_NS : PROBE_INTERVAL_ADD_NS;
        }
        if (now - lastProbeNs < interval) {
            return;
        }
        lastProbeNs = now;
        if (burstRemaining > 0) {
            burstRemaining--;
        }
        h.sendProbe(PROBE_TAG | (probeSeq.incrementAndGet() & ~PROBE_TAG_MASK));
    }

    private void expireProbes(long now) {
        if (probesInFlight.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, Long>> it = probesInFlight.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > PROBE_TIMEOUT_NS) {
                it.remove();
            }
        }
    }

    private void startBurst() {
        burstRemaining = PROBE_BURST_COUNT;
        lastProbeNs = 0;
    }

    // ------------------------------------------------------------------ estimator

    private synchronized void addSample(double rttMs, long nanoTime) {
        samples[sampleIndex] = rttMs;
        sampleIndex = (sampleIndex + 1) % WINDOW;
        if (sampleCount < WINDOW) {
            sampleCount++;
        }
        samplesSinceCommand++;
        lastSampleNs = nanoTime;

        // A real route change (e.g. wifi hiccup or server move): if the newest samples all sit
        // well away from the estimate on the same side, drop the old history and re-converge now.
        if (baseMs >= 0 && sampleCount >= STEP_CHANGE_RUN) {
            int above = 0;
            int below = 0;
            for (int i = 1; i <= STEP_CHANGE_RUN; i++) {
                double s = samples[Math.floorMod(sampleIndex - i, WINDOW)];
                if (s > baseMs + STEP_CHANGE_MS) above++;
                if (s < baseMs - STEP_CHANGE_MS) below++;
            }
            if (above == STEP_CHANGE_RUN || below == STEP_CHANGE_RUN) {
                double[] recent = new double[STEP_CHANGE_RUN];
                for (int i = 0; i < STEP_CHANGE_RUN; i++) {
                    recent[i] = samples[Math.floorMod(sampleIndex - STEP_CHANGE_RUN + i, WINDOW)];
                }
                Arrays.fill(samples, 0);
                System.arraycopy(recent, 0, samples, 0, STEP_CHANGE_RUN);
                sampleCount = STEP_CHANGE_RUN;
                sampleIndex = STEP_CHANGE_RUN % WINDOW;
            }
        }

        double[] sorted = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(sorted);
        // Trimmed mean: ignores spikes without the bias a minimum or a slow EMA would have.
        int trim = sampleCount / 4;
        double sum = 0;
        int n = 0;
        for (int i = trim; i < sampleCount - trim; i++) {
            sum += sorted[i];
            n++;
        }
        baseMs = sum / n;
        double var = 0;
        for (int i = 0; i < sampleCount; i++) {
            double d = sorted[i] - baseMs;
            var += d * d;
        }
        jitterMs = Math.sqrt(var / sampleCount);
    }

    private boolean hasFreshBase(long now) {
        return baseMs >= 0 && now - lastSampleNs <= BASE_FRESH_NS;
    }

    private void clearMeasurements() {
        probesInFlight.clear();
        Arrays.fill(samples, 0);
        sampleCount = 0;
        sampleIndex = 0;
        samplesSinceCommand = 0;
        baseMs = -1;
        jitterMs = 0;
        lastSampleNs = 0;
        lastProbeNs = 0;
        burstRemaining = 0;
    }

    private static int tabLatency() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return -1;
        }
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (nh == null) {
            return -1;
        }
        PlayerListEntry self = nh.getPlayerListEntry(client.player.getUuid());
        return self == null ? -1 : self.getLatency();
    }

    private static double clamp(double ms) {
        return Math.min(MAX_ADDED_PING_MS, Math.max(0, ms));
    }

    private static int clamp(int ms) {
        return Math.min(MAX_ADDED_PING_MS, Math.max(0, ms));
    }

    // ------------------------------------------------------------------ status

    public Mode getMode() {
        return mode;
    }

    public boolean isServerEnabled() {
        return serverEnabled;
    }

    public boolean isOffMode() {
        return mode == Mode.OFF;
    }

    public synchronized boolean isAddingDelay(int amount) {
        return mode == Mode.ADD && addAmount == clamp(amount);
    }

    public synchronized boolean isTotalPingTarget(int amount) {
        return mode == Mode.TOTAL && totalTarget == Math.max(0, amount);
    }

    public int getCurrentDelayMs() {
        return isActive() ? (int) Math.round(appliedDelayMs) : 0;
    }

    public synchronized int getBasePing() {
        return baseMs < 0 ? 0 : (int) Math.round(baseMs);
    }

    public synchronized double getBasePingPrecise() {
        return baseMs;
    }

    public synchronized double getJitterMs() {
        return jitterMs;
    }

    public synchronized int getTotalPing() {
        return getBasePing() + getCurrentDelayMs();
    }

    public String getFollowName() {
        return followName;
    }

    public synchronized int getTotalTarget() {
        return totalTarget;
    }

    public EqualizerChannelHandler getHandler() {
        return handler;
    }

    public synchronized String getStatusMessage() {
        if (!serverEnabled) {
            return "Accurate Ping Equalizer: disabled by server";
        }
        if (mode == Mode.OFF) {
            return "Accurate Ping Equalizer: OFF";
        }
        String modeStr;
        if (mode == Mode.ADD) {
            modeStr = "ADD +" + addAmount + "ms";
        } else if (followName != null) {
            modeStr = "MATCH " + followName + " " + totalTarget + "ms" + (followLost ? " (left, holding)" : "");
        } else {
            modeStr = "TOTAL " + totalTarget + "ms";
        }
        if (baseMs < 0) {
            return String.format("Accurate Ping Equalizer: %s | Added: %dms | Measuring base ping...",
                    modeStr, getCurrentDelayMs());
        }
        String pacing = "";
        EqualizerChannelHandler h = handler;
        if (h != null) {
            pacing = String.format(" | Timing error: %.1fms", h.getRecentTimingErrorMs());
        }
        String combat = isInCombat(System.nanoTime()) ? " | Combat lock" : "";
        if (OptimizerPause.isPaused()) {
            combat += " | Optimizers paused";
        }
        double input = InputDelay.currentMs();
        if (input > 0) {
            combat += String.format(" | Input delay %.0fms", input);
        }
        return String.format("Accurate Ping Equalizer: %s | Base: %.0fms (±%.0f) | Added: %.0fms | Total: %.0fms%s%s",
                modeStr, baseMs, jitterMs, appliedDelayMs, baseMs + appliedDelayMs, pacing, combat);
    }

    public synchronized String getServerSwitchStatusMessage() {
        return switch (mode) {
            case OFF -> "PE Status; OFF";
            case ADD -> String.format("PE Status; ADD: %d ms", getCurrentDelayMs());
            case TOTAL -> String.format("PE Status; Total: %d ms", totalTarget);
        };
    }
}
