package net.cpvpbreezy.accuratepingequalizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/** Small shared settings file: config/accuratepingequalizer.properties. */
public final class ApeConfig {

    private static final Properties PROPS = load();

    private ApeConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("accuratepingequalizer.properties");
    }

    private static Properties load() {
        Properties p = new Properties();
        Path f = file();
        if (Files.exists(f)) {
            try (var in = Files.newBufferedReader(f)) {
                p.load(in);
            } catch (IOException ignored) {
                // fall back to defaults
            }
        }
        return p;
    }

    public static synchronized String get(String key, String def) {
        String v = PROPS.getProperty(key);
        return v == null ? def : v.trim();
    }

    public static synchronized void set(String key, String value) {
        PROPS.setProperty(key, value);
        try (var out = Files.newBufferedWriter(file())) {
            PROPS.store(out, "Accurate Ping Equalizer settings");
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("AccuratePingEqualizer").warn("Could not save config: {}", e.toString());
        }
    }
}
