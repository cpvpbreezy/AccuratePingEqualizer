package net.cpvpbreezy.accuratepingequalizer.net;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.cpvpbreezy.accuratepingequalizer.EqualizerState;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;

/**
 * Sits just in front of Minecraft's packet handler and holds packets back by the
 * configured amount in each direction.
 *
 * <p>Timing is done by a dedicated pacing thread rather than the network event
 * loop: it parks until shortly before a packet is due, spins the last fraction
 * of a millisecond, then hands the packet back to the event loop. The spin window
 * is calibrated against this machine's real timer resolution, and any remaining
 * systematic lateness (thread hand-off, coarse timers) is measured on every packet
 * and corrected with a small integral controller, so the average delay lands on
 * the requested value.</p>
 */
public final class EqualizerChannelHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "accurate_ping_equalizer";

    private static final long DISPATCH_SLACK_NS = TimeUnit.MICROSECONDS.toNanos(50);
    private static final long MIN_SPIN_NS = TimeUnit.MICROSECONDS.toNanos(300);
    private static final long MAX_SPIN_NS = TimeUnit.MILLISECONDS.toNanos(2);
    /** Combat packets get a wider busy-wait so even a rare late OS wake-up can't shift a hit or anchor click. */
    private static final long COMBAT_SPIN_NS = TimeUnit.MILLISECONDS.toNanos(3);
    private static final long MAX_COMPENSATION_NS = TimeUnit.MILLISECONDS.toNanos(16);
    /**
     * Step of the median tracker. The correction converges on the MEDIAN lateness, not the mean: a rare
     * packet that the OS wakes up late must not pull every other packet early (that showed up as a total
     * ping a few ms below target under load).
     */
    private static final double MEDIAN_STEP_NS = 15_000;

    private static volatile long spinWindowNs = -1;

    /** Ceiling for the adaptive busy-wait: one coarse Windows timer tick (15.6 ms) plus margin. */
    private static final long MAX_ADAPTIVE_SPIN_NS = TimeUnit.MICROSECONDS.toNanos(17_000);
    private static final long SPIN_MARGIN_NS = TimeUnit.MICROSECONDS.toNanos(250);
    /** Busy-waits longer than this yield the core between checks instead of hard spinning. */
    private static final long YIELD_ABOVE_NS = TimeUnit.MICROSECONDS.toNanos(300);

    /**
     * Running estimate of how late a timed sleep really wakes up right now. It jumps up instantly when a
     * wake-up is late and only decays slowly, so if Windows coarsens the timer (background windows on
     * Windows 11, or another program changing the timer) the busy-wait widens before the next packet.
     */
    private double wakeOvershootNs = -1;

    private final ConcurrentLinkedQueue<Task> outbound = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Task> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean outboundDispatchQueued = new AtomicBoolean();
    private final AtomicBoolean inboundDispatchQueued = new AtomicBoolean();

    private volatile boolean active = false;
    private volatile ChannelHandlerContext context;
    private volatile Thread pacer;

    // Written only on the event loop.
    private volatile double outboundCompensationNs = 0;
    private volatile double inboundCompensationNs = 0;
    private volatile double recentAbsErrorNs = 0;
    // Latest due time released per direction; a packet due earlier than this was held up behind
    // another one (TCP-style bunching), which is intended and must not train the timing correction.
    private long lastOutboundDueNs = Long.MIN_VALUE;
    private long lastInboundDueNs = Long.MIN_VALUE;

    private static final class Task {
        final Object msg;
        final ChannelPromise promise;
        final long targetNs;
        final long dueNs;
        final boolean delayed;
        final boolean combat;

        Task(Object msg, ChannelPromise promise, long targetNs, long dueNs, boolean delayed) {
            this(msg, promise, targetNs, dueNs, delayed, false);
        }

        Task(Object msg, ChannelPromise promise, long targetNs, long dueNs, boolean delayed, boolean combat) {
            this.msg = msg;
            this.promise = promise;
            this.targetNs = targetNs;
            this.dueNs = dueNs;
            this.delayed = delayed;
            this.combat = combat;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void setActive(boolean active) {
        this.active = active;
        if (active) {
            EqualizerState.get().attachHandler(this);
            startPacer();
        } else {
            flushAll();
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.context = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        active = false;
        stopPacer();
        drainEverythingNow(ctx);
        EqualizerState.get().detachHandler(this);
        this.context = null;
    }

    // ------------------------------------------------------------------ outbound

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!active || !(msg instanceof Packet<?> packet) || packet.transitionsNetworkState()) {
            if (outbound.isEmpty()) {
                super.write(ctx, msg, promise);
            } else {
                // Keep ordering: anything after queued packets has to wait its turn.
                long now = System.nanoTime();
                enqueue(outbound, new Task(msg, promise, now, now, false));
            }
            return;
        }
        EqualizerState state = EqualizerState.get();
        long now = System.nanoTime();
        boolean combat = isCombatOutbound(packet);
        if (combat) {
            state.markCombat(now);
        }
        long delay = state.outboundDelayNanos();
        if (delay <= 0 && outbound.isEmpty()) {
            super.write(ctx, msg, promise);
            return;
        }
        long target = now + delay;
        long due = Math.max(now, target - (long) outboundCompensationNs);
        enqueue(outbound, new Task(msg, promise, target, due, delay > 0, combat));
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof PingResultS2CPacket pong && EqualizerState.isProbe(pong.startTime())) {
            // Our own measurement probe: time it at the network edge and keep it away from vanilla.
            EqualizerState.get().onProbeReply(pong.startTime(), System.nanoTime());
            return;
        }
        if (!active || !(msg instanceof Packet<?> packet) || packet.transitionsNetworkState()) {
            if (inbound.isEmpty()) {
                super.channelRead(ctx, msg);
            } else {
                long now = System.nanoTime();
                enqueue(inbound, new Task(msg, null, now, now, false));
            }
            return;
        }
        EqualizerState state = EqualizerState.get();
        long now = System.nanoTime();
        boolean combat = isCombatInbound(packet);
        if (isCombatFeedback(packet)) {
            state.markCombat(now);
        }
        long delay = state.inboundDelayNanos();
        if (delay <= 0 && inbound.isEmpty()) {
            super.channelRead(ctx, msg);
            return;
        }
        long target = now + delay;
        long due = Math.max(now, target - (long) inboundCompensationNs);
        enqueue(inbound, new Task(msg, null, target, due, delay > 0, combat));
    }

    // ------------------------------------------------------------------ combat packets

    /** Hits, crystal/anchor placing and breaking, anchor charging/detonating, item use, swings, hotbar swaps and inventory clicks. */
    private static boolean isCombatOutbound(Packet<?> p) {
        return p instanceof PlayerInteractEntityC2SPacket
                || p instanceof PlayerInteractBlockC2SPacket
                || p instanceof PlayerInteractItemC2SPacket
                || p instanceof PlayerActionC2SPacket
                || p instanceof HandSwingC2SPacket
                || p instanceof UpdateSelectedSlotC2SPacket
                // inventory: totem / item swaps (ghosting depends on these crossing in flight)
                || p instanceof ClickSlotC2SPacket
                || p instanceof CloseHandledScreenC2SPacket;
    }

    /** What the server sends back in a fight: damage, explosions, crystals appearing/vanishing, anchor block states, knockback, inventory/totem state. */
    private static boolean isCombatInbound(Packet<?> p) {
        return isCombatFeedback(p)
                || p instanceof EntitySpawnS2CPacket
                || p instanceof EntitiesDestroyS2CPacket
                || p instanceof BlockUpdateS2CPacket
                || p instanceof ChunkDeltaUpdateS2CPacket
                || p instanceof PlayerActionResponseS2CPacket
                || p instanceof EntityStatusS2CPacket
                || p instanceof EntityVelocityUpdateS2CPacket
                || p instanceof EntityEquipmentUpdateS2CPacket
                // inventory state coming back from the server
                || p instanceof ScreenHandlerSlotUpdateS2CPacket
                || p instanceof InventoryS2CPacket
                || p instanceof SetPlayerInventoryS2CPacket
                || p instanceof UpdateSelectedSlotS2CPacket;
    }

    /** Inbound packets that by themselves mean you are in a fight. */
    private static boolean isCombatFeedback(Packet<?> p) {
        return p instanceof EntityDamageS2CPacket
                || p instanceof DamageTiltS2CPacket
                || p instanceof ExplosionS2CPacket
                || p instanceof HealthUpdateS2CPacket;
    }

    // ------------------------------------------------------------------ probes

    /** Writes a measurement probe straight to the wire, skipping the delay queue. */
    public void sendProbe(long tag) {
        ChannelHandlerContext ctx = context;
        if (ctx == null || !active || !ctx.channel().isOpen()) {
            return;
        }
        ctx.executor().execute(() -> {
            if (!ctx.channel().isOpen()) {
                return;
            }
            EqualizerState.get().onProbeSent(tag, System.nanoTime());
            ctx.writeAndFlush(new QueryPingC2SPacket(tag));
        });
    }

    // ------------------------------------------------------------------ queues

    private void enqueue(ConcurrentLinkedQueue<Task> queue, Task task) {
        queue.offer(task);
        Thread t = pacer;
        if (t != null) {
            LockSupport.unpark(t);
        } else {
            // No pacer (shutting down): just release in order.
            requestDispatch(queue == outbound);
        }
    }

    private void requestDispatch(boolean out) {
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return;
        }
        AtomicBoolean flag = out ? outboundDispatchQueued : inboundDispatchQueued;
        if (flag.compareAndSet(false, true)) {
            ctx.executor().execute(out ? this::dispatchOutbound : this::dispatchInbound);
        }
    }

    private void dispatchOutbound() {
        outboundDispatchQueued.set(false);
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return;
        }
        boolean wrote = false;
        boolean release = pacer == null;
        Task task;
        while ((task = outbound.peek()) != null) {
            long now = System.nanoTime();
            if (!release && task.dueNs > now + DISPATCH_SLACK_NS) {
                break;
            }
            outbound.poll();
            if (task.delayed && task.dueNs >= lastOutboundDueNs) {
                outboundCompensationNs = updateCompensation(outboundCompensationNs, now - task.targetNs);
            }
            lastOutboundDueNs = Math.max(lastOutboundDueNs, task.dueNs);
            if (ctx.channel().isOpen()) {
                ctx.write(task.msg, task.promise);
                wrote = true;
            }
        }
        if (wrote && ctx.channel().isOpen()) {
            ctx.flush();
        }
        wakePacerIfPending(outbound);
    }

    /**
     * The pacer may be asleep until the OTHER direction's next packet; if this queue still holds
     * packets, it must re-plan now or they would wait for that unrelated wake-up.
     */
    private void wakePacerIfPending(ConcurrentLinkedQueue<Task> queue) {
        if (!queue.isEmpty()) {
            Thread t = pacer;
            if (t != null) {
                LockSupport.unpark(t);
            }
        }
    }

    private void dispatchInbound() {
        inboundDispatchQueued.set(false);
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            return;
        }
        boolean release = pacer == null;
        Task task;
        while ((task = inbound.peek()) != null) {
            long now = System.nanoTime();
            if (!release && task.dueNs > now + DISPATCH_SLACK_NS) {
                break;
            }
            inbound.poll();
            if (task.delayed && task.dueNs >= lastInboundDueNs) {
                inboundCompensationNs = updateCompensation(inboundCompensationNs, now - task.targetNs);
            }
            lastInboundDueNs = Math.max(lastInboundDueNs, task.dueNs);
            if (ctx.channel().isOpen()) {
                ctx.fireChannelRead(task.msg);
            }
        }
        wakePacerIfPending(inbound);
    }

    private double updateCompensation(double current, long errorNs) {
        // Typical (median) absolute timing error, for /ape status.
        double abs = Math.abs(errorNs);
        recentAbsErrorNs += abs > recentAbsErrorNs ? MEDIAN_STEP_NS : -MEDIAN_STEP_NS;
        recentAbsErrorNs = Math.max(0, recentAbsErrorNs);
        double next = current + (errorNs > 0 ? MEDIAN_STEP_NS : -MEDIAN_STEP_NS);
        return Math.max(0, Math.min(MAX_COMPENSATION_NS, next));
    }

    public double getRecentTimingErrorMs() {
        return recentAbsErrorNs / 1_000_000.0;
    }

    public double getOutboundCompensationMs() {
        return outboundCompensationNs / 1_000_000.0;
    }

    public double getInboundCompensationMs() {
        return inboundCompensationNs / 1_000_000.0;
    }

    // ------------------------------------------------------------------ pacer

    private synchronized void startPacer() {
        if (pacer != null) {
            return;
        }
        Thread t = new Thread(this::pacerLoop, "AccuratePingEqualizer-Pacer");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        pacer = t;
        t.start();
    }

    private synchronized void stopPacer() {
        Thread t = pacer;
        pacer = null;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    private void pacerLoop() {
        wakeOvershootNs = calibrateSpinWindow() - SPIN_MARGIN_NS;
        Thread self = Thread.currentThread();
        while (pacer == self) {
            long now = System.nanoTime();
            long next = Long.MAX_VALUE;
            boolean nextIsCombat = false;
            boolean anyDue = false;
            Task o = outbound.peek();
            if (o != null) {
                if (o.dueNs <= now + DISPATCH_SLACK_NS) {
                    requestDispatch(true);
                    anyDue = true;
                } else {
                    next = o.dueNs;
                    nextIsCombat = o.combat;
                }
            }
            Task i = inbound.peek();
            if (i != null) {
                if (i.dueNs <= now + DISPATCH_SLACK_NS) {
                    requestDispatch(false);
                    anyDue = true;
                } else if (i.dueNs < next) {
                    next = i.dueNs;
                    nextIsCombat = i.combat;
                }
            }
            if (anyDue) {
                // A head is being released right now; the packets behind it may be due within
                // microseconds, so re-plan immediately instead of sleeping on the other queue.
                Thread.onSpinWait();
                continue;
            }
            if (next == Long.MAX_VALUE) {
                if (o == null && i == null) {
                    LockSupport.park(this);
                } else {
                    // Head is due and waiting for the event loop to take it.
                    Thread.onSpinWait();
                }
                continue;
            }
            long wait = next - now;
            long window = adaptiveSpinNs();
            if (nextIsCombat) {
                window = Math.max(window, COMBAT_SPIN_NS);
            }
            if (wait > window) {
                long sleep = wait - window;
                long before = System.nanoTime();
                LockSupport.parkNanos(this, sleep);
                long over = System.nanoTime() - before - sleep;
                if (over > 0) {
                    // (A negative value means a new packet woke us early: says nothing about the timer.)
                    recordWakeOvershoot(over);
                }
            } else if (wait > YIELD_ABOVE_NS) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void recordWakeOvershoot(long overNs) {
        if (overNs > wakeOvershootNs) {
            wakeOvershootNs = overNs;
        } else {
            wakeOvershootNs += (overNs - wakeOvershootNs) * 0.002;
        }
    }

    private long adaptiveSpinNs() {
        long w = (long) wakeOvershootNs + SPIN_MARGIN_NS;
        return Math.max(MIN_SPIN_NS, Math.min(MAX_ADAPTIVE_SPIN_NS, w));
    }

    public double getSpinWindowMs() {
        return adaptiveSpinNs() / 1_000_000.0;
    }

    /** Measures how late parkNanos really wakes up on this machine and spins for that long instead. */
    private static long calibrateSpinWindow() {
        long cached = spinWindowNs;
        if (cached > 0) {
            return cached;
        }
        int runs = 25;
        long[] over = new long[runs];
        long request = TimeUnit.MICROSECONDS.toNanos(500);
        for (int r = 0; r < runs; r++) {
            long start = System.nanoTime();
            LockSupport.parkNanos(request);
            over[r] = Math.max(0, System.nanoTime() - start - request);
        }
        Arrays.sort(over);
        long p90 = over[(int) (runs * 0.9)];
        long window = Math.max(MIN_SPIN_NS, Math.min(MAX_SPIN_NS, p90 + TimeUnit.MICROSECONDS.toNanos(200)));
        spinWindowNs = window;
        org.slf4j.LoggerFactory.getLogger("AccuratePingEqualizer")
                .info("Timer calibration: p90 park overshoot {}us, spin window {}us", p90 / 1000, window / 1000);
        return window;
    }

    public static long getSpinWindowMicros() {
        return spinWindowNs / 1000;
    }

    // ------------------------------------------------------------------ flushing

    public void flushAll() {
        ChannelHandlerContext ctx = context;
        if (ctx == null) {
            outbound.clear();
            inbound.clear();
            return;
        }
        if (ctx.executor().inEventLoop()) {
            drainEverythingNow(ctx);
        } else {
            ctx.executor().execute(() -> drainEverythingNow(ctx));
        }
    }

    private void drainEverythingNow(ChannelHandlerContext ctx) {
        Task task;
        boolean open = ctx.channel().isOpen();
        boolean wrote = false;
        while ((task = outbound.poll()) != null) {
            if (open) {
                ctx.write(task.msg, task.promise);
                wrote = true;
            }
        }
        if (wrote) {
            ctx.flush();
        }
        while ((task = inbound.poll()) != null) {
            if (open) {
                ctx.fireChannelRead(task.msg);
            }
        }
    }
}
