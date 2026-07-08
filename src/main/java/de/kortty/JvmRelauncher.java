package de.kortty;

import de.kortty.core.JvmLaunchProfileStore;
import de.kortty.model.JvmResourceProfile;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applies the opt-in {@link JvmResourceProfile} by relaunching the packaged app once at startup
 * with overriding {@code _JAVA_OPTIONS}. Heap/GC options cannot change in a running JVM, and the
 * launcher's baked-in options live inside the signed macOS bundle (editing them would break
 * notarization), so the only safe way to raise the heap or switch the collector is to re-exec the
 * native launcher with an overriding environment.
 *
 * <p>Deliberately dependency-light: it must run before any logging/JavaFX/JAXB is touched, so it
 * touches only {@code java.*}, the profile enum and the tiny {@link JvmLaunchProfileStore}. Any
 * failure is swallowed — a relaunch problem must never stop the app from starting normally.</p>
 */
public final class JvmRelauncher {

    /** Set on the relaunched child so it does not relaunch again (loop guard). */
    private static final String RELAUNCHED_MARKER = "KORTTY_JVM_RELAUNCHED";

    private JvmRelauncher() {
    }

    /**
     * If a non-default JVM profile is selected in a packaged build, relaunches the app with the
     * profile's JVM options and terminates this process. Returns normally (letting startup
     * continue) when no relaunch is needed or possible.
     */
    public static void maybeRelaunch(String[] args) {
        try {
            if (System.getenv(RELAUNCHED_MARKER) != null) {
                return; // already the relaunched child
            }
            // jpackage sets this to the native launcher path; absent => dev run or plain-jar, where
            // JVM options are the user's responsibility and there is nothing to re-exec.
            String appPath = System.getProperty("jpackage.app-path");
            if (appPath == null || appPath.isBlank()) {
                return;
            }
            Path configDir = Path.of(System.getProperty("user.home", ""), ".kortty");
            JvmResourceProfile profile = JvmLaunchProfileStore.read(configDir);
            if (profile == null || profile == JvmResourceProfile.BALANCED) {
                return;
            }
            String javaOptions = profile.resolveJavaOptions(totalPhysicalMemoryBytes());
            if (javaOptions == null || javaOptions.isBlank()) {
                return;
            }

            List<String> command = new ArrayList<>();
            command.add(appPath);
            Collections.addAll(command, args);
            ProcessBuilder builder = new ProcessBuilder(command);
            // _JAVA_OPTIONS is parsed after the launcher's baked options, so its -Xmx / +UseZGC win.
            builder.environment().put("_JAVA_OPTIONS", javaOptions);
            builder.environment().put(RELAUNCHED_MARKER, "1");
            builder.inheritIO();
            builder.start();
            System.exit(0);
        } catch (Throwable ignored) {
            // Fall through to a normal, default-heap startup on any failure.
        }
    }

    /** Physical RAM in bytes via {@code com.sun.management} (module jdk.management), or 0 on failure. */
    private static long totalPhysicalMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return 0L;
    }
}
