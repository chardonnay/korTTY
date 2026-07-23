package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression harness for the AI Manager reasoning picker. Selecting a profile fills the
 * connection-mode, URL and model widgets, and each of those change listeners rebuilds the reasoning
 * options from the half-populated form. Those rebuilds must not write back into the profile, or the
 * stored level is replaced by Disabled before the form applies it — and the close-time autosave then
 * persists that loss. Also covers switching between two profiles, where the previous profile's combo
 * value used to leak into the next one. Run via the {@code aiManagerReasoningPersistenceSmoke}
 * Gradle task. Exit 0 = OK.
 */
public final class AiManagerReasoningPersistenceSmoke {

    private static final String REASONING_MODEL = "gpt-oss-20b";

    private AiManagerReasoningPersistenceSmoke() {
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

                AiProfile high = reasoningProfile("p-high", "High profile", AiReasoningEffort.HIGH);
                AiProfile low = reasoningProfile("p-low", "Low profile", AiReasoningEffort.LOW);

                ObservableList<AiProfile> profiles = get(dialog, "profiles");
                ListView<AiProfile> profileListView = get(dialog, "profileListView");
                ComboBox<AiReasoningEffort> reasoningCombo = get(dialog, "reasoningCombo");

                profiles.addAll(high, low);

                // --- A: selecting a profile must show and keep its stored level. ---
                profileListView.getSelectionModel().select(high);
                System.out.println("A: combo=" + reasoningCombo.getValue() + " profile=" + high.getReasoningEffort());
                if (reasoningCombo.getValue() != AiReasoningEffort.HIGH) {
                    throw new AssertionError("A: combo lost the stored level: " + reasoningCombo.getValue());
                }
                if (high.getReasoningEffort() != AiReasoningEffort.HIGH) {
                    throw new AssertionError("A: loading overwrote the profile: " + high.getReasoningEffort());
                }

                // --- B: the close-time snapshot must write the stored level back, not Disabled. ---
                invoke(dialog, "snapshotSelectedProfileState");
                System.out.println("B: profile=" + high.getReasoningEffort());
                if (high.getReasoningEffort() != AiReasoningEffort.HIGH) {
                    throw new AssertionError("B: snapshot downgraded the level: " + high.getReasoningEffort());
                }

                // --- C: switching profiles must not leak the previous combo value. ---
                profileListView.getSelectionModel().select(low);
                invoke(dialog, "snapshotSelectedProfileState");
                System.out.println("C: combo=" + reasoningCombo.getValue() + " profile=" + low.getReasoningEffort());
                if (reasoningCombo.getValue() != AiReasoningEffort.LOW
                    || low.getReasoningEffort() != AiReasoningEffort.LOW) {
                    throw new AssertionError("C: switching profiles lost the level: combo="
                        + reasoningCombo.getValue() + " profile=" + low.getReasoningEffort());
                }
                if (high.getReasoningEffort() != AiReasoningEffort.HIGH) {
                    throw new AssertionError("C: switching away corrupted the first profile: "
                        + high.getReasoningEffort());
                }

                // --- D: a real user pick must still reach the profile. ---
                reasoningCombo.getSelectionModel().select(AiReasoningEffort.MEDIUM);
                invoke(dialog, "snapshotSelectedProfileState");
                System.out.println("D: profile=" + low.getReasoningEffort());
                if (low.getReasoningEffort() != AiReasoningEffort.MEDIUM) {
                    throw new AssertionError("D: user pick did not reach the profile: "
                        + low.getReasoningEffort());
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

    private static AiProfile reasoningProfile(String id, String name, AiReasoningEffort effort) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("http://127.0.0.1:1234");
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel(REASONING_MODEL);
        profile.setReasoningEffort(effort);
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
