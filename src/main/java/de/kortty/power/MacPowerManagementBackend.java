package de.kortty.power;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Native macOS App Nap and idle-system-sleep integration. */
final class MacPowerManagementBackend implements PowerManagementBackend {

    // NSActivityUserInitiatedAllowingIdleSystemSleep. It disables App Nap for the process activity
    // but intentionally does not keep the whole Mac awake.
    private static final long NS_ACTIVITY_USER_INITIATED_ALLOWING_IDLE_SYSTEM_SLEEP = 0x00EFFFFFL;
    private static final Path CAFFEINATE = Path.of("/usr/bin/caffeinate");

    private final NativeLibrary objectiveC = NativeLibrary.getInstance("objc");
    private final Function objcGetClass = objectiveC.getFunction("objc_getClass");
    private final Function selRegisterName = objectiveC.getFunction("sel_registerName");
    private final Function objcMsgSend = objectiveC.getFunction("objc_msgSend");
    private final Function autoreleasePoolPush = objectiveC.getFunction("objc_autoreleasePoolPush");
    private final Function autoreleasePoolPop = objectiveC.getFunction("objc_autoreleasePoolPop");

    private Process caffeinateProcess;
    private Pointer appNapActivity;

    @Override
    public boolean supportsSystemSleepPrevention() {
        return Files.isExecutable(CAFFEINATE);
    }

    @Override
    public boolean supportsAppNapPrevention() {
        return true;
    }

    @Override
    public synchronized void setSystemSleepPrevented(boolean prevented) throws Exception {
        boolean running = caffeinateProcess != null && caffeinateProcess.isAlive();
        if (prevented == running) {
            return;
        }
        if (!prevented) {
            stopCaffeinate();
            return;
        }

        caffeinateProcess = null;
        Process process = new ProcessBuilder(
            CAFFEINATE.toString(),
            "-i",
            "-w",
            Long.toString(ProcessHandle.current().pid()))
            .redirectErrorStream(true)
            .start();
        if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
            String output = readProcessOutput(process);
            throw new IllegalStateException(
                "caffeinate exited with code " + process.exitValue()
                    + (output.isBlank() ? "" : ": " + output.trim()));
        }
        caffeinateProcess = process;
    }

    private void stopCaffeinate() throws InterruptedException {
        Process process = caffeinateProcess;
        caffeinateProcess = null;
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public synchronized void setAppNapPrevented(boolean prevented) {
        if (prevented == (appNapActivity != null)) {
            return;
        }
        Pointer pool = autoreleasePoolPush.invokePointer(new Object[0]);
        try {
            Pointer processInfoClass = objcGetClass.invokePointer(new Object[] {"NSProcessInfo"});
            Pointer processInfo = sendPointer(processInfoClass, "processInfo");
            if (!prevented) {
                sendVoid(processInfo, "endActivity:", appNapActivity);
                sendVoid(appNapActivity, "release");
                appNapActivity = null;
                return;
            }

            Pointer stringClass = objcGetClass.invokePointer(new Object[] {"NSString"});
            Pointer reason = sendPointer(stringClass, "stringWithUTF8String:", "korTTY terminal or scheduler activity");
            Pointer activity = sendPointer(
                processInfo,
                "beginActivityWithOptions:reason:",
                NS_ACTIVITY_USER_INITIATED_ALLOWING_IDLE_SYSTEM_SLEEP,
                reason);
            if (activity == null || Pointer.nativeValue(activity) == 0L) {
                throw new IllegalStateException("NSProcessInfo did not return an activity token");
            }
            sendVoid(activity, "retain");
            appNapActivity = activity;
        } finally {
            autoreleasePoolPop.invokeVoid(new Object[] {pool});
        }
    }

    private Pointer selector(String name) {
        return selRegisterName.invokePointer(new Object[] {name});
    }

    private Pointer sendPointer(Pointer receiver, String selector, Object... arguments) {
        Object[] invocation = new Object[arguments.length + 2];
        invocation[0] = receiver;
        invocation[1] = selector(selector);
        System.arraycopy(arguments, 0, invocation, 2, arguments.length);
        return objcMsgSend.invokePointer(invocation);
    }

    private void sendVoid(Pointer receiver, String selector, Object... arguments) {
        Object[] invocation = new Object[arguments.length + 2];
        invocation[0] = receiver;
        invocation[1] = selector(selector);
        System.arraycopy(arguments, 0, invocation, 2, arguments.length);
        objcMsgSend.invokeVoid(invocation);
    }

    @Override
    public synchronized void close() throws Exception {
        Exception failure = null;
        try {
            setAppNapPrevented(false);
        } catch (Exception e) {
            failure = e;
        }
        try {
            setSystemSleepPrevented(false);
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        objectiveC.close();
        if (failure != null) {
            throw failure;
        }
    }
}
