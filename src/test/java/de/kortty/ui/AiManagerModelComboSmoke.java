package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
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
 * Headless regression harness for the AI Manager model picker. The dialog is intentionally NOT
 * shown: without a skin, nothing but {@link ComboBoxEditorSync} updates the editor, so this proves
 * a popup pick lands in the editor synchronously — the guarantee that defeats the macOS editable
 * ComboBox commit race where the stale editor text reverts the clicked value. Also covers the
 * "async model refresh completes after the pick" race and free typing, and verifies the profile
 * snapshot. Run via the {@code aiManagerModelComboSmoke} Gradle task. Exit 0 = OK.
 */
public final class AiManagerModelComboSmoke {

    private static final String LIVE_MODEL = "qwen/qwen3-coder-next";

    private AiManagerModelComboSmoke() {
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

                AiProfile local = new AiProfile();
                local.setId("p-local");
                local.setName("local");
                local.setConnectionMode(AiConnectionMode.HTTP_API);
                local.setApiUrl("http://127.0.0.1:1234");
                local.setModelSelectionMode(AiModelSelectionMode.AUTO);

                ObservableList<AiProfile> profiles = get(dialog, "profiles");
                ListView<AiProfile> profileListView = get(dialog, "profileListView");
                ComboBox<String> modelCombo = get(dialog, "modelCombo");

                profiles.add(local);
                profileListView.getSelectionModel().select(local);

                // Async refresh delivers the live model list (refreshLocalModels callback).
                invoke(dialog, "preserveModelItems", new Class<?>[] {List.class}, List.of(LIVE_MODEL));
                if (!modelCombo.getItems().contains(LIVE_MODEL)) {
                    throw new AssertionError("live model missing from items: " + modelCombo.getItems());
                }

                // --- A: popup pick must land in the editor SYNCHRONOUSLY (no skin attached). ---
                modelCombo.getSelectionModel().select(LIVE_MODEL);
                System.out.println("A: value=" + modelCombo.getValue()
                    + " editor='" + modelCombo.getEditor().getText() + "'");
                if (!LIVE_MODEL.equals(modelCombo.getEditor().getText())) {
                    throw new AssertionError("A: editor did not follow the picked value: '"
                        + modelCombo.getEditor().getText() + "'");
                }

                // --- B: a refresh (↻ / URL edit) completing AFTER the pick must not clobber it. ---
                invoke(dialog, "preserveModelItems", new Class<?>[] {List.class}, List.of(LIVE_MODEL));
                System.out.println("B: editor='" + modelCombo.getEditor().getText() + "'");
                if (!LIVE_MODEL.equals(modelCombo.getEditor().getText())) {
                    throw new AssertionError("B: refresh clobbered the picked model: '"
                        + modelCombo.getEditor().getText() + "'");
                }

                // Snapshot into the profile (Save / close / profile switch) keeps the pick.
                invoke(dialog, "snapshotSelectedProfileState", new Class<?>[] {});
                if (local.getModelSelectionMode() != AiModelSelectionMode.MANUAL
                    || !LIVE_MODEL.equals(local.getModel())) {
                    throw new AssertionError("snapshot lost the picked model: mode="
                        + local.getModelSelectionMode() + " model=" + local.getModel());
                }

                // --- C: free typing must stay untouched by the sync and snapshot as typed. ---
                modelCombo.getEditor().setText("my-custom-model");
                invoke(dialog, "snapshotSelectedProfileState", new Class<?>[] {});
                System.out.println("C: profile model=" + local.getModel());
                if (local.getModelSelectionMode() != AiModelSelectionMode.MANUAL
                    || !"my-custom-model".equals(local.getModel())) {
                    throw new AssertionError("typed custom model lost: mode="
                        + local.getModelSelectionMode() + " model=" + local.getModel());
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

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
        throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }
}
