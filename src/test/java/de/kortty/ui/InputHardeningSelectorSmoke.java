package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.WorkflowScriptSupport.InputHardeningConfig;
import de.kortty.core.WorkflowScriptSupport.InputHardeningOption;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression harness for the {@link InputHardeningSelector}. The panel's contract is that
 * the master toggle gates everything: with the toggle off the config must be disabled and the
 * sub-option grid and size row must be disabled, and the spinner value must reach the config as
 * bytes only while the FILE_SIZE_LIMIT sub-option is ticked. Run via the
 * {@code inputHardeningSelectorSmoke} Gradle task. Exit 0 = OK.
 */
public final class InputHardeningSelectorSmoke {

    private InputHardeningSelectorSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                InputHardeningSelector selector = new InputHardeningSelector();
                CheckBox enableCheck = get(selector, "enableCheck");
                Map<InputHardeningOption, CheckBox> checks = get(selector, "checks");
                Spinner<Integer> spinner = get(selector, "maxFileSizeSpinner");
                // Children order: master check, sub-option grid, size row, button row.
                var grid = selector.getChildren().get(1);
                var sizeRow = selector.getChildren().get(2);

                // --- A: without the app singleton the panel starts off and fully gated. ---
                System.out.println("A: master=" + enableCheck.isSelected()
                    + " gridDisabled=" + grid.isDisabled() + " sizeRowDisabled=" + sizeRow.isDisabled());
                if (enableCheck.isSelected() || !grid.isDisabled() || !sizeRow.isDisabled()) {
                    throw new AssertionError("A: panel must start off with grid and size row disabled");
                }
                if (selector.currentConfig().isEnabled() || selector.selectedCount() != 0) {
                    throw new AssertionError("A: master off must mean disabled config and count 0");
                }

                // --- B: ticking the master enables the grid and yields the full default config. ---
                AtomicInteger fired = new AtomicInteger();
                selector.setOnSelectionChanged(fired::incrementAndGet);
                enableCheck.setSelected(true);
                InputHardeningConfig config = selector.currentConfig();
                System.out.println("B: enabled=" + config.isEnabled()
                    + " options=" + config.options().size() + " bytes=" + config.maxFileSizeBytes());
                if (grid.isDisabled() || sizeRow.isDisabled()) {
                    throw new AssertionError("B: master on must enable grid and size row");
                }
                if (!config.isEnabled() || config.options().size() != InputHardeningOption.values().length
                    || config.maxFileSizeBytes() != 10L * 1_048_576L) {
                    throw new AssertionError("B: default config must carry all sub-options at 10 MB");
                }
                if (fired.get() == 0 || selector.selectedCount() != InputHardeningOption.values().length) {
                    throw new AssertionError("B: selection callback or live count did not fire");
                }

                // --- C: the spinner value reaches the config as bytes. ---
                spinner.getValueFactory().setValue(25);
                if (selector.currentConfig().maxFileSizeBytes() != 25L * 1_048_576L) {
                    throw new AssertionError("C: spinner MB value must convert to bytes");
                }

                // --- C2: zero is a valid unlimited value and must not be replaced by the default. ---
                spinner.getValueFactory().setValue(0);
                if (selector.currentConfig().maxFileSizeBytes() != 0L) {
                    throw new AssertionError("C2: spinner value 0 must reach the config as unlimited");
                }

                // --- D: unticking FILE_SIZE_LIMIT drops it from the config and re-gates the row. ---
                checks.get(InputHardeningOption.FILE_SIZE_LIMIT).setSelected(false);
                config = selector.currentConfig();
                System.out.println("D: options=" + config.options() + " sizeRowDisabled=" + sizeRow.isDisabled());
                if (config.options().contains(InputHardeningOption.FILE_SIZE_LIMIT) || !sizeRow.isDisabled()) {
                    throw new AssertionError("D: FILE_SIZE_LIMIT off must gate the size row again");
                }

                // --- D2: Clear/All alter the effective config, not only the visible checkboxes. ---
                HBox buttons = (HBox) selector.getChildren().get(3);
                Button allButton = (Button) buttons.getChildren().get(0);
                Button clearButton = (Button) buttons.getChildren().get(1);
                clearButton.fire();
                if (selector.currentConfig().isEnabled() || selector.selectedCount() != 0) {
                    throw new AssertionError("D2: Clear must disable the effective guard while the master stays on");
                }
                allButton.fire();
                if (!selector.currentConfig().isEnabled()
                        || selector.selectedCount() != InputHardeningOption.values().length) {
                    throw new AssertionError("D2: All must restore every input-hardening sub-option");
                }

                // --- E: unticking the master flips back to disabled regardless of sub-options. ---
                enableCheck.setSelected(false);
                if (selector.currentConfig().isEnabled() || selector.selectedCount() != 0) {
                    throw new AssertionError("E: master off must always mean a disabled config");
                }

                // --- F: unsupported declarative targets must be visibly and effectively disabled. ---
                enableCheck.setSelected(true);
                selector.setSupported(false);
                if (!selector.isDisable() || selector.isSupported()
                        || selector.currentConfig().isEnabled() || selector.selectedCount() != 0) {
                    throw new AssertionError("F: unsupported target must disable the selector and effective config");
                }
                selector.setSupported(true);
                if (selector.isDisable() || !selector.isSupported()
                        || !selector.currentConfig().isEnabled() || selector.selectedCount() == 0) {
                    throw new AssertionError("F: supported target must restore the saved per-run selection");
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, String.valueOf(t));
            } finally {
                done.countDown();
            }
        });

        if (!done.await(30, TimeUnit.SECONDS)) {
            failure.compareAndSet(null, "Timed out waiting for the FX checks");
        }
        Platform.exit();
        if (failure.get() != null) {
            System.err.println(failure.get());
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
}
