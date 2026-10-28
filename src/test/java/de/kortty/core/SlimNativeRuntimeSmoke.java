package de.kortty.core;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exercises JNA, a real local PTY and jSerialComm's native using the target-only JARs staged for jpackage. */
public final class SlimNativeRuntimeSmoke {

    private SlimNativeRuntimeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Class<?> nativeClass = Class.forName("com.sun.jna.Native");
        Class<?> platformClass = Class.forName("com.sun.jna.Platform");
        int pointerSize = nativeClass.getField("POINTER_SIZE").getInt(null);
        String jnaArch = (String) platformClass.getField("ARCH").get(null);
        if (pointerSize <= 0 || jnaArch == null || jnaArch.isBlank()) {
            throw new AssertionError("JNA did not load its target native library");
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] command = windows
            ? new String[]{"cmd.exe", "/c", "echo", "kortty-pty-smoke"}
            : new String[]{"/bin/echo", "kortty-pty-smoke"};
        PtyProcess process = new PtyProcessBuilder(command)
            .setConsole(false)
            .setRedirectErrorStream(true)
            .start();
        CompletableFuture<byte[]> outputRead = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Target-only pty4j process timed out");
        }
        String output = new String(outputRead.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        if (process.exitValue() != 0 || !output.contains("kortty-pty-smoke")) {
            throw new AssertionError("Target-only pty4j process failed: exit=" + process.exitValue() + ", output=" + output);
        }
        // Enumerating ports loads jSerialComm's native; an empty list is fine (CI runners have no ports).
        Class<?> serialPortClass = Class.forName("com.fazecast.jSerialComm.SerialPort");
        Object ports = serialPortClass.getMethod("getCommPorts").invoke(null);
        if (ports == null) {
            throw new AssertionError("jSerialComm did not load its target native library");
        }
        System.out.println("Slim JNA/pty4j/jSerialComm native runtime smoke passed for " + jnaArch + ".");
    }
}
