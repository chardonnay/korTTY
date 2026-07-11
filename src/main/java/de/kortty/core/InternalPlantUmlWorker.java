package de.kortty.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Private launcher mode used when a stripped jpackage runtime has no {@code bin/java} command.
 *
 * <p>The native korTTY launcher starts this worker in a fresh process backed by the bundled JVM.
 * The worker accepts only the pinned, checksum-verified PlantUML jar and invokes its regular CLI
 * entry point with an isolated class loader. It deliberately initializes neither JavaFX nor the
 * normal korTTY application.</p>
 *
 * <p>This class is public only so the launcher in the parent package can call it; it is not a
 * supported application API.</p>
 */
public final class InternalPlantUmlWorker {

    public static final String ARGUMENT = "--kortty-internal-plantuml-worker";
    private static final String PLANTUML_MAIN_CLASS = "net.sourceforge.plantuml.Run";

    private InternalPlantUmlWorker() {
    }

    public static boolean isInvocation(String[] arguments) {
        return arguments != null && arguments.length > 0 && ARGUMENT.equals(arguments[0]);
    }

    /** Executes the internal invocation and returns the process exit status. */
    public static int run(String[] arguments) {
        if (!isInvocation(arguments) || arguments.length < 2) {
            System.err.println("Invalid internal PlantUML worker invocation.");
            return 2;
        }

        try {
            Path jar = Path.of(arguments[1]);
            if (!PlantUmlRenderService.isVerifiedPlantUmlJar(jar)) {
                System.err.println("Refusing to load an unverified PlantUML jar.");
                return 2;
            }

            String[] plantUmlArguments = Arrays.copyOfRange(arguments, 2, arguments.length);
            return invokePlantUml(jar, plantUmlArguments);
        } catch (Exception e) {
            System.err.println("PlantUML worker failed: " + usefulMessage(e));
            return 1;
        }
    }

    private static int invokePlantUml(Path jar, String[] arguments) throws Exception {
        URL jarUrl = jar.toUri().toURL();
        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{jarUrl}, ClassLoader.getPlatformClassLoader())) {

            Thread.currentThread().setContextClassLoader(loader);
            Class<?> mainClass = Class.forName(PLANTUML_MAIN_CLASS, true, loader);
            Method main = mainClass.getMethod("main", String[].class);
            try {
                main.invoke(null, (Object) arguments);
                return 0;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw e;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
        }
    }

    private static String usefulMessage(Exception exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
    }
}
