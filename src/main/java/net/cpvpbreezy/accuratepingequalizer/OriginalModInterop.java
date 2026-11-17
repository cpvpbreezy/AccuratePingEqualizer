package net.cpvpbreezy.accuratepingequalizer;

import java.lang.reflect.Method;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Lets Accurate Ping Equalizer run next to Ravenclaw's original Ping Equalizer.
 * Both add delay through their own network handler, so if both were on the delays would stack.
 * Whichever one was turned on last wins and the other is switched off. Everything is reflective
 * and fails soft: if the original changes, we simply stop coordinating with it.
 */
public final class OriginalModInterop {

    public static final String ORIGINAL_ID = "ravenclawspingequalizer";
    private static final String STATE_CLASS = "net.ravenclaw.ravenclawspingequalizer.PingEqualizerState";

    private static Boolean loaded;
    private static Object instance;
    private static Method isOffMode;
    private static Method setOff;
    private static Method isServerEnabled;
    private static boolean broken;

    private OriginalModInterop() {}

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = FabricLoader.getInstance().isModLoaded(ORIGINAL_ID);
        }
        return loaded;
    }

    private static boolean ready() {
        if (!isLoaded() || broken) {
            return false;
        }
        if (instance != null) {
            return true;
        }
        try {
            Class<?> c = Class.forName(STATE_CLASS);
            instance = c.getMethod("getInstance").invoke(null);
            isOffMode = c.getMethod("isOffMode");
            setOff = c.getMethod("setOff");
            isServerEnabled = c.getMethod("isServerEnabled");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            broken = true;
            org.slf4j.LoggerFactory.getLogger("AccuratePingEqualizer")
                    .warn("Could not hook Ravenclaw's Ping Equalizer; delays may stack if both are on: {}", e.toString());
            return false;
        }
    }

    /** True when the original is installed and currently adding delay. */
    public static boolean isOriginalActive() {
        if (!ready()) {
            return false;
        }
        try {
            return !(boolean) isOffMode.invoke(instance);
        } catch (ReflectiveOperationException e) {
            broken = true;
            return false;
        }
    }

    /** Turns the original off. Returns true if it was on. */
    public static boolean turnOriginalOff() {
        if (!isOriginalActive()) {
            return false;
        }
        try {
            setOff.invoke(instance);
            return true;
        } catch (ReflectiveOperationException e) {
            broken = true;
            return false;
        }
    }

    /** The original's view of the server's enable/disable switch (it owns the shared channel when installed). */
    public static boolean originalServerEnabled() {
        if (!ready()) {
            return true;
        }
        try {
            return (boolean) isServerEnabled.invoke(instance);
        } catch (ReflectiveOperationException e) {
            broken = true;
            return true;
        }
    }
}
