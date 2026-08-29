package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.ScriptLanguageMixSupport;
import de.kortty.core.ScriptLanguageMixSupport.HostFormat;
import de.kortty.core.SnippetAiWorkflowSupport.MigrationPlan;
import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.control.ComboBox;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless regression harness for {@link TargetLanguageSelector} — the one place where the
 * host-format rule reaches the UI. What it pins:
 * <ul>
 *   <li>a mixed plain script offers a whole-script migration over all migration targets;</li>
 *   <li>a pipeline whose steps all speak one language offers <b>no</b> language migration at all,
 *       only the platform row — embedded Bash in a pipeline is by design, not a defect;</li>
 *   <li>a pipeline with disagreeing steps offers the shell-ish targets only, pre-ticked;</li>
 *   <li>the target platform is never preselected, in any state;</li>
 *   <li>a host that shows the panel collapsed never arms it at all.</li>
 * </ul>
 * Run via the {@code targetLanguageSelectorSmoke} Gradle task. Exit 0 = OK.
 */
public final class TargetLanguageSelectorSmoke {

    private static final String MIXED_BASH = """
        #!/usr/bin/env bash
        set -euo pipefail
        perl <<'PERL'
        print "hi\\n";
        PERL
        echo done
        """;

    private static final String UNIFORM_PIPELINE = """
        trigger:
          - main
        pool:
          vmImage: ubuntu-latest
        steps:
          - bash: make build
          - bash: make test
        """;

    private static final String MIXED_PIPELINE = """
        trigger:
          - main
        pool:
          vmImage: ubuntu-latest
        steps:
          - bash: |
              make build
            displayName: Build
          - pwsh: |
              Write-Host "publish"
            displayName: Publish
        """;

    private TargetLanguageSelectorSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TargetLanguageSelector selector = new TargetLanguageSelector(true);
                ComboBox<ScriptLanguage> languageCombo = get(selector, "languageCombo");
                ComboBox<HostFormat> platformCombo = get(selector, "platformCombo");

                // --- A: a mixed plain script offers the full whole-script migration. ---
                selector.setDetectedMix(ScriptLanguageMixSupport.detect("bash", MIXED_BASH));
                System.out.println("A: offers=" + selector.hasAnythingToOffer()
                    + " enabled=" + selector.isEnabled() + " targets=" + languageCombo.getItems().size());
                if (!selector.hasAnythingToOffer() || !selector.isEnabled()) {
                    throw new AssertionError("A: a mixed script must offer a migration");
                }
                if (languageCombo.getItems().size() != ScriptLanguage.migrationTargets().size()) {
                    throw new AssertionError("A: a plain script must offer every migration target");
                }
                if (selector.selectedHostFormat() != null) {
                    throw new AssertionError("A: no platform row for a plain script");
                }
                MigrationPlan planA = selector.buildPlan();
                if (planA.isNoOp() || planA.changesHostFormat()) {
                    throw new AssertionError("A: expected a plain whole-script plan, got " + planA.modes());
                }

                // --- B: a single-language pipeline offers no migration, only the platform row. ---
                selector.setDetectedMix(ScriptLanguageMixSupport.detect("yaml", UNIFORM_PIPELINE));
                System.out.println("B: enabled=" + selector.isEnabled()
                    + " offers=" + selector.hasAnythingToOffer()
                    + " platform=" + platformCombo.getValue());
                if (selector.isEnabled() || selector.selectedTarget() != null) {
                    throw new AssertionError("B: embedded Bash in a pipeline must not be offered a migration");
                }
                if (!selector.hasAnythingToOffer()) {
                    throw new AssertionError("B: the platform conversion must stay reachable");
                }
                if (!selector.buildPlan().isNoOp()) {
                    throw new AssertionError("B: nothing chosen must mean nothing to do");
                }

                // --- C: disagreeing steps offer the shell-ish targets only. ---
                selector.setDetectedMix(ScriptLanguageMixSupport.detect("yaml", MIXED_PIPELINE));
                System.out.println("C: enabled=" + selector.isEnabled()
                    + " targets=" + languageCombo.getItems());
                if (languageCombo.getItems().size() >= ScriptLanguage.migrationTargets().size()) {
                    throw new AssertionError("C: step targets must be a narrowed list");
                }
                if (languageCombo.getItems().contains(ScriptLanguage.APPLESCRIPT)) {
                    throw new AssertionError("C: AppleScript is not a pipeline step language");
                }
                if (!selector.isEnabled() || selector.buildPlan().isNoOp()) {
                    throw new AssertionError("C: a detected step mix must arrive pre-ticked");
                }
                if (selector.buildPlan().changesHostFormat()) {
                    throw new AssertionError("C: unifying steps must not change the platform");
                }

                // --- D: the target platform is never preselected, in any state. ---
                for (String document : new String[] {MIXED_BASH, UNIFORM_PIPELINE, MIXED_PIPELINE}) {
                    selector.setDetectedMix(ScriptLanguageMixSupport.detect(null, document));
                    if (selector.selectedHostFormat() != null) {
                        throw new AssertionError("D: a platform conversion must never be preselected");
                    }
                    if (selector.buildPlan().changesHostFormat()) {
                        throw new AssertionError("D: no plan may convert the platform on its own");
                    }
                }

                // --- F: an unarmed host (Full code analysis, security report) must never pre-arm. ---
                // Its panel sits collapsed among other panels; arming it there would silently rewrite
                // the whole script into another language on "Apply selected".
                TargetLanguageSelector unarmed = new TargetLanguageSelector(false);
                for (String document : new String[] {MIXED_BASH, MIXED_PIPELINE}) {
                    unarmed.setDetectedMix(ScriptLanguageMixSupport.detect(null, document));
                    System.out.println("F: enabled=" + unarmed.isEnabled()
                        + " offers=" + unarmed.hasAnythingToOffer()
                        + " count=" + unarmed.selectedCount());
                    if (unarmed.isEnabled() || unarmed.selectedTarget() != null) {
                        throw new AssertionError("F: a collapsed panel must not arm itself");
                    }
                    if (!unarmed.buildPlan().isNoOp() || unarmed.selectedCount() != 0) {
                        throw new AssertionError("F: an unarmed panel must contribute no migration");
                    }
                    if (!unarmed.hasAnythingToOffer()) {
                        throw new AssertionError("F: the offer must still be reachable by hand");
                    }
                }

                // --- E: choosing a platform produces a conversion plan. ---
                selector.setDetectedMix(ScriptLanguageMixSupport.detect("yaml", UNIFORM_PIPELINE));
                platformCombo.setValue(HostFormat.GITHUB_ACTIONS);
                MigrationPlan planE = selector.buildPlan();
                System.out.println("E: modes=" + planE.modes());
                if (!planE.changesHostFormat() || planE.isNoOp()) {
                    throw new AssertionError("E: an explicit platform choice must produce a conversion");
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
