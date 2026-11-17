package net.cpvpbreezy.accuratepingequalizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Client "latency hiding" mods make crystals vanish and anchors explode on your screen the instant you
 * click, instead of when the server's answer arrives. That is exactly the wait a higher ping is made of,
 * so while the equalizer is on they are paused (the same thing a server does when it opts out of them),
 * and restored when it is turned off:
 * <ul>
 *   <li>Marlow's Crystal Optimizer: its own "server opted out" switch.</li>
 *   <li>ClientSideCrystals: its instant / seamless prediction settings (not saved to its config).</li>
 *   <li>HerosAnchorOptimizer: its instant fake-anchor swap is blocked (see ClientWorldMixin).</li>
 * </ul>
 * Everything is reflective and fails soft: an unknown version of one of these mods is simply left alone.
 */
public final class OptimizerPause {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("AccuratePingEqualizer");

    /** User setting: pause the optimizers while equalizing (default) or let them run. */
    private static boolean pauseWhileEqualizing = loadSetting();

    private static boolean paused = false;
    private static int reassertTicks = 0;

    // Marlow
    private static Object marlowCache;
    private static Method marlowIsOptedOut;
    private static Method marlowSetOptedOut;
    private static boolean marlowWasOptedOut;
    private static boolean marlowBroken;

    // ClientSideCrystals
    private static Object cscConfig;
    private static Field cscInstant;
    private static Field cscSeamless;
    private static boolean cscWasInstant;
    private static boolean cscWasSeamless;
    private static boolean cscBroken;

    // HerosAnchorOptimizer
    private static Block fakeAnchor;
    private static boolean fakeAnchorLooked;

    private OptimizerPause() {}

    /** Called every client tick. */
    public static void tick(boolean equalizerActive) {
        boolean want = equalizerActive && pauseWhileEqualizing;
        if (want && !paused) {
            pause();
        } else if (!want && paused) {
            restore();
        } else if (paused && ++reassertTicks >= 20) {
            // Marlow re-reads its per-server opt-out state when you join a server; keep it paused.
            reassertTicks = 0;
            marlowSet(true);
        }
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isPauseEnabled() {
        return pauseWhileEqualizing;
    }

    public static void setPauseEnabled(boolean enabled) {
        pauseWhileEqualizing = enabled;
        saveSetting();
    }

    /** Which optimizer mods are installed, for messages. */
    public static String installedNames() {
        StringBuilder sb = new StringBuilder();
        var fl = FabricLoader.getInstance();
        if (fl.isModLoaded("marlowcrystal")) sb.append("Marlow's Crystal Optimizer, ");
        if (fl.isModLoaded("clientsidecrystals")) sb.append("ClientSideCrystals, ");
        if (fl.isModLoaded("herosanchoroptimizer")) sb.append("HerosAnchorOptimizer, ");
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 2);
    }

    /** Used by ClientWorldMixin: true if this block change is HerosAnchorOptimizer's instant fake anchor. */
    public static boolean shouldBlockFakeAnchor(BlockState state) {
        if (!paused) {
            return false;
        }
        if (!fakeAnchorLooked) {
            fakeAnchorLooked = true;
            Identifier id = Identifier.of("herosanchoroptimizer", "fake_anchor");
            if (Registries.BLOCK.containsId(id)) {
                fakeAnchor = Registries.BLOCK.get(id);
            }
        }
        return fakeAnchor != null && state.isOf(fakeAnchor);
    }

    private static void pause() {
        paused = true;
        reassertTicks = 0;
        // Marlow
        if (hookMarlow()) {
            try {
                marlowWasOptedOut = (boolean) marlowIsOptedOut.invoke(marlowCache);
            } catch (ReflectiveOperationException e) {
                marlowWasOptedOut = false;
            }
            marlowSet(true);
        }
        // ClientSideCrystals
        if (hookCsc()) {
            try {
                cscWasInstant = cscInstant.getBoolean(cscConfig);
                cscWasSeamless = cscSeamless.getBoolean(cscConfig);
                cscInstant.setBoolean(cscConfig, false);
                cscSeamless.setBoolean(cscConfig, false);
            } catch (ReflectiveOperationException e) {
                cscBroken = true;
            }
        }
    }

    private static void restore() {
        paused = false;
        if (marlowCache != null && !marlowBroken) {
            marlowSet(marlowWasOptedOut);
        }
        if (cscConfig != null && !cscBroken) {
            try {
                cscInstant.setBoolean(cscConfig, cscWasInstant);
                cscSeamless.setBoolean(cscConfig, cscWasSeamless);
            } catch (ReflectiveOperationException e) {
                cscBroken = true;
            }
        }
    }

    private static void marlowSet(boolean optedOut) {
        if (marlowCache == null || marlowBroken) {
            return;
        }
        try {
            marlowSetOptedOut.invoke(marlowCache, optedOut);
        } catch (ReflectiveOperationException e) {
            marlowBroken = true;
        }
    }

    private static boolean hookMarlow() {
        if (marlowCache != null) {
            return true;
        }
        if (marlowBroken || !FabricLoader.getInstance().isModLoaded("marlowcrystal")) {
            return false;
        }
        try {
            Class<?> main = Class.forName("com.deathmotion.marlowcrystal.MarlowCrystal");
            Object inst = main.getMethod("getInstance").invoke(null);
            Object cache = main.getMethod("getOptOutCache").invoke(inst);
            marlowIsOptedOut = cache.getClass().getMethod("isOptedOut");
            marlowSetOptedOut = cache.getClass().getMethod("setOptedOut", boolean.class);
            marlowCache = cache;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            marlowBroken = true;
            LOG.warn("Could not pause Marlow's Crystal Optimizer (unknown version): {}", e.toString());
            return false;
        }
    }

    private static boolean hookCsc() {
        if (cscConfig != null) {
            return true;
        }
        if (cscBroken || !FabricLoader.getInstance().isModLoaded("clientsidecrystals")) {
            return false;
        }
        try {
            Class<?> mgr = Class.forName("me.clientsidecrystals.config.ConfigManager");
            Object cfg = mgr.getField("config").get(null);
            cscInstant = cfg.getClass().getField("instantEnabled");
            cscSeamless = cfg.getClass().getField("seamlessEnabled");
            cscConfig = cfg;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            cscBroken = true;
            LOG.warn("Could not pause ClientSideCrystals (unknown version): {}", e.toString());
            return false;
        }
    }

    // ------------------------------------------------------------------ setting

    private static boolean loadSetting() {
        return Boolean.parseBoolean(ApeConfig.get("pauseOptimizers", "true"));
    }

    private static void saveSetting() {
        ApeConfig.set("pauseOptimizers", Boolean.toString(pauseWhileEqualizing));
    }
}
