package de.kortty.ui;

import de.kortty.core.AiReasoningSupport;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression harness for the AI Manager reasoning picker. Selecting a profile fills the
 * connection-mode, URL and model widgets, and each of those change listeners rebuilds the reasoning
 * options from the half-populated form. Those rebuilds must not write back into the profile, or the
 * stored level is replaced by Disabled before the form applies it — and the close-time autosave then
 * persists that loss. Also covers switching between two profiles, where the previous profile's combo
 * value used to leak into the next one.
 *
 * <p>Also covers the asynchronous model-list refresh. Its callback rebuilds the model combo items,
 * which transiently empties the editor text; the resulting reasoning rebuild sees a profile without
 * a model, offers Disabled only, and used to write that back over a level the endpoint really
 * supports. A profile whose levels come from endpoint discovery (an LM Studio model whose name
 * carries no reasoning hint) loses them for good that way. Run via the
 * {@code aiManagerReasoningPersistenceSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiManagerReasoningPersistenceSmoke {

    private static final String REASONING_MODEL = "gpt-oss-20b";
    /** No reasoning hint in the name: its levels can only come from endpoint discovery. */
    private static final String DISCOVERED_MODEL = "qwen/qwen3.8-27b";

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

                // --- E: the async model-list callback must not drop discovered levels. ---
                AiProfile discovered = discoveredProfile("p-discovered", "Discovered profile");
                profiles.add(discovered);
                profileListView.getSelectionModel().select(discovered);
                if (reasoningCombo.getValue() != AiReasoningEffort.XHIGH) {
                    throw new AssertionError("E: discovered level not shown: " + reasoningCombo.getValue());
                }
                invokeWithModels(dialog, "preserveModelItems", List.of(DISCOVERED_MODEL, "other/model"));
                System.out.println("E: combo=" + reasoningCombo.getValue()
                    + " items=" + reasoningCombo.getItems()
                    + " profile=" + discovered.getReasoningEffort());
                if (reasoningCombo.getItems().size() != 5) {
                    throw new AssertionError("E: model refresh dropped the discovered options: "
                        + reasoningCombo.getItems());
                }
                if (reasoningCombo.getValue() != AiReasoningEffort.XHIGH
                    || discovered.getReasoningEffort() != AiReasoningEffort.XHIGH) {
                    throw new AssertionError("E: model refresh reset the level: combo="
                        + reasoningCombo.getValue() + " profile=" + discovered.getReasoningEffort());
                }

                // --- F: the close-time snapshot must keep the discovered level too. ---
                invoke(dialog, "snapshotSelectedProfileState");
                System.out.println("F: profile=" + discovered.getReasoningEffort());
                if (discovered.getReasoningEffort() != AiReasoningEffort.XHIGH) {
                    throw new AssertionError("F: snapshot downgraded the discovered level: "
                        + discovered.getReasoningEffort());
                }

                // --- G: fields the profile's own connection mode does not use must not count. ---
                // The editor fills the CLI provider for every profile, so an HTTP profile saved
                // before that happened used to lose its discovered levels on the next load.
                AiProfile contaminated = discoveredProfile("p-contaminated", "Contaminated key");
                contaminated.setCliProviderId("codex-cli");
                contaminated.setCliArgumentsTemplate("exec\n--sandbox\nread-only");
                contaminated.setCliExecutablePath("/usr/local/bin/codex");
                profiles.add(contaminated);
                profileListView.getSelectionModel().select(contaminated);
                System.out.println("G: combo=" + reasoningCombo.getValue()
                    + " items=" + reasoningCombo.getItems());
                if (reasoningCombo.getItems().size() != 5
                    || reasoningCombo.getValue() != AiReasoningEffort.XHIGH) {
                    throw new AssertionError("G: unused CLI fields invalidated the discovery: items="
                        + reasoningCombo.getItems() + " combo=" + reasoningCombo.getValue());
                }

                // --- H: typing a model name must not fire one endpoint lookup per keystroke. ---
                ComboBox<String> modelCombo = get(dialog, "modelCombo");
                for (int i = 1; i <= "another/model".length(); i++) {
                    modelCombo.getEditor().setText("another/model".substring(0, i));
                }
                java.util.Set<String> attempted = get(dialog, "attemptedReasoningDiscoveryKeys");
                String pending = get(dialog, "pendingReasoningDiscoveryKey");
                System.out.println("H: attempted=" + attempted.size() + " pending=" + (pending != null));
                if (!attempted.isEmpty()) {
                    throw new AssertionError("H: typing dispatched " + attempted.size()
                        + " endpoint lookups instead of waiting for the editor to settle");
                }
                if (pending == null) {
                    throw new AssertionError("H: no lookup was scheduled for the typed model");
                }
                // The half-typed model has no discovered levels and none from its name either, but
                // the level stays put until the scheduled lookup has had its say.
                if (contaminated.getReasoningEffort() != AiReasoningEffort.XHIGH) {
                    throw new AssertionError("H: a pending lookup let the level be reset to "
                        + contaminated.getReasoningEffort());
                }
                invoke(dialog, "snapshotSelectedProfileState");
                if (contaminated.getReasoningEffort() != AiReasoningEffort.XHIGH) {
                    throw new AssertionError("H: the close-time snapshot reset the level to "
                        + contaminated.getReasoningEffort() + " while a lookup was still pending");
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

    /** Mirrors an LM Studio profile whose levels were read from the endpoint's model metadata. */
    private static AiProfile discoveredProfile(String id, String name) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName(name);
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("http://127.0.0.1:1234");
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel(DISCOVERED_MODEL);
        profile.setDiscoveredReasoningEfforts(List.of(
            AiReasoningEffort.DISABLED,
            AiReasoningEffort.NONE,
            AiReasoningEffort.LOW,
            AiReasoningEffort.MEDIUM,
            AiReasoningEffort.XHIGH));
        profile.setReasoningDiscoveryKey(AiReasoningSupport.discoveryKey(profile));
        profile.setReasoningEffort(AiReasoningEffort.XHIGH);
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

    private static void invokeWithModels(Object target, String methodName, List<String> models)
        throws Exception {

        Method method = target.getClass().getDeclaredMethod(methodName, List.class);
        method.setAccessible(true);
        method.invoke(target, models);
    }
}
