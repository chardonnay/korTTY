package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression harness for the AI Manager's per-profile request timeout. The override is two
 * widgets (check box + spinner) backed by one nullable field, so the two states that must never be
 * confused are "follow the global value" (null) and "never time out" (0). Loading a profile,
 * switching profiles and the close-time snapshot each have to keep them apart, or a profile that
 * opted out of the global limit silently gets one back. Run via the
 * {@code aiManagerRequestTimeoutSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiManagerRequestTimeoutSmoke {

    private AiManagerRequestTimeoutSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                AiManagerDialog dialog = new AiManagerDialog(null);

                AiProfile inheriting = timeoutProfile("p-global", "Follows global", null);
                AiProfile unlimited = timeoutProfile("p-none", "Never times out", 0);
                AiProfile limited = timeoutProfile("p-limited", "Own limit", 45);

                ObservableList<AiProfile> profiles = get(dialog, "profiles");
                ListView<AiProfile> profileListView = get(dialog, "profileListView");
                CheckBox overrideCheck = get(dialog, "profileRequestTimeoutOverrideCheck");
                Spinner<Integer> timeoutSpinner = get(dialog, "profileRequestTimeoutSpinner");

                profiles.addAll(inheriting, unlimited, limited);

                // --- A: a profile without an override shows an unchecked box. ---
                profileListView.getSelectionModel().select(inheriting);
                System.out.println("A: checked=" + overrideCheck.isSelected()
                    + " spinner=" + timeoutSpinner.getValue());
                if (overrideCheck.isSelected()) {
                    throw new AssertionError("A: inherited timeout shown as an override");
                }
                invoke(dialog, "snapshotSelectedProfileState");
                if (inheriting.getRequestTimeoutMinutes() != null) {
                    throw new AssertionError("A: snapshot invented an override: "
                        + inheriting.getRequestTimeoutMinutes());
                }

                // --- B: an explicit 0 means "never time out" and must not read as "inherit". ---
                profileListView.getSelectionModel().select(unlimited);
                System.out.println("B: checked=" + overrideCheck.isSelected()
                    + " spinner=" + timeoutSpinner.getValue());
                if (!overrideCheck.isSelected() || timeoutSpinner.getValue() != 0) {
                    throw new AssertionError("B: the no-timeout override did not load: checked="
                        + overrideCheck.isSelected() + " spinner=" + timeoutSpinner.getValue());
                }
                invoke(dialog, "snapshotSelectedProfileState");
                if (!Integer.valueOf(0).equals(unlimited.getRequestTimeoutMinutes())) {
                    throw new AssertionError("B: snapshot dropped the no-timeout override: "
                        + unlimited.getRequestTimeoutMinutes());
                }

                // --- C: switching profiles must not leak the previous profile's value. ---
                profileListView.getSelectionModel().select(limited);
                System.out.println("C: checked=" + overrideCheck.isSelected()
                    + " spinner=" + timeoutSpinner.getValue());
                if (!overrideCheck.isSelected() || timeoutSpinner.getValue() != 45) {
                    throw new AssertionError("C: the profile's own limit did not load: "
                        + timeoutSpinner.getValue());
                }
                invoke(dialog, "snapshotSelectedProfileState");
                if (!Integer.valueOf(45).equals(limited.getRequestTimeoutMinutes())
                    || !Integer.valueOf(0).equals(unlimited.getRequestTimeoutMinutes())
                    || inheriting.getRequestTimeoutMinutes() != null) {
                    throw new AssertionError("C: switching corrupted a profile: limited="
                        + limited.getRequestTimeoutMinutes()
                        + " unlimited=" + unlimited.getRequestTimeoutMinutes()
                        + " inheriting=" + inheriting.getRequestTimeoutMinutes());
                }

                // --- D: a real user edit must reach the profile. ---
                timeoutSpinner.getValueFactory().setValue(90);
                invoke(dialog, "snapshotSelectedProfileState");
                System.out.println("D: profile=" + limited.getRequestTimeoutMinutes());
                if (!Integer.valueOf(90).equals(limited.getRequestTimeoutMinutes())) {
                    throw new AssertionError("D: user edit did not reach the profile: "
                        + limited.getRequestTimeoutMinutes());
                }

                // --- E: clearing the override returns the profile to the global value. ---
                overrideCheck.setSelected(false);
                invoke(dialog, "snapshotSelectedProfileState");
                System.out.println("E: profile=" + limited.getRequestTimeoutMinutes());
                if (limited.getRequestTimeoutMinutes() != null) {
                    throw new AssertionError("E: clearing the override kept a value: "
                        + limited.getRequestTimeoutMinutes());
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, String.valueOf(t));
            } finally {
                done.countDown();
            }
        });

        if (!done.await(30, TimeUnit.SECONDS)) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        Platform.exit();
        if (failure.get() != null) {
            System.err.println("SMOKE FAILED: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK");
    }

    private static AiProfile timeoutProfile(String id, String name, Integer timeoutMinutes) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("http://127.0.0.1:1234");
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel("local-model");
        profile.setRequestTimeoutMinutes(timeoutMinutes);
        return profile;
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }
}
