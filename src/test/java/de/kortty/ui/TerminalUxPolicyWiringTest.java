package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Where the terminal-UX policy switches take effect: the OSC 52 writer asks the policy on every write,
 * multi-exec refuses joins at the coordinator (so every menu, palette entry, shortcut and dashboard
 * action is covered) and broadcast mode refuses to switch on at the split pane, and the Settings
 * controls carry the managed-by-organization lock. Building the dialogs needs a JavaFX toolkit, so the
 * wiring is checked in the source (CRLF-safe); the decisions themselves run.
 */
class TerminalUxPolicyWiringTest {

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static EffectivePolicy osc52(Boolean allowed) {
        PolicyFile file = new PolicyFile(1, "ACME", Map.of(),
            List.of(PolicyRule.builder().allowOsc52ClipboardWrite(allowed).build()),
            List.of(), List.of(), List.of(), List.of());
        return EffectivePolicy.resolve(file, new PolicyIdentity() {
            @Override
            public String userName() {
                return "u";
            }

            @Override
            public Set<String> osGroups() {
                return Set.of();
            }
        });
    }

    @Test
    void anOsc52WriteNeedsTheSettingAndThePolicy() {
        GlobalSettings on = new GlobalSettings();
        on.setOsc52ClipboardWriteEnabled(true);
        GlobalSettings off = new GlobalSettings();

        assertThat(TerminalClipboardWriter.allowed(on, EffectivePolicy.unrestricted())).isTrue();
        assertThat(TerminalClipboardWriter.allowed(on, osc52(true))).isTrue();
        assertWithMessage("a hand-edited settings file between two clamps cannot let a write through")
            .that(TerminalClipboardWriter.allowed(on, osc52(false))).isFalse();
        assertThat(TerminalClipboardWriter.allowed(on, EffectivePolicy.lockdown())).isFalse();
        assertThat(TerminalClipboardWriter.allowed(off, EffectivePolicy.unrestricted())).isFalse();
        assertThat(TerminalClipboardWriter.allowed(null, EffectivePolicy.unrestricted())).isFalse();
    }

    @Test
    void theOsc52WriterAsksThePolicyOnEveryWrite() throws IOException {
        assertThat(source("src/main/java/de/kortty/ui/TerminalClipboardWriter.java"))
            .contains("write(write, allowed(current, de.kortty.policy.PolicyManager.effective()), clipboard)");
    }

    @Test
    void multiExecRefusesJoinsAtTheCoordinatorButNeverLeaving() throws IOException {
        String coordinator = source("src/main/java/de/kortty/ui/MultiExecCoordinator.java");
        assertThat(coordinator).contains("this(() -> de.kortty.policy.PolicyManager.effective().multiExecAllowed());");
        assertThat(coordinator).contains("if (!membership.contains(pane) && joinRefused()) {");
        assertThat(coordinator).contains("if (included && joinRefused()) {");
        String stop = coordinator.substring(coordinator.indexOf("public void stop()"));
        assertWithMessage("Stop Multi-exec must work whatever the policy says")
            .that(stop.substring(0, stop.indexOf('}'))).doesNotContain("joinRefused");
    }

    @Test
    void broadcastModeRefusesToSwitchOnAtTheSplitPane() throws IOException {
        String splitPane = source("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");
        assertThat(splitPane).contains("if (enabled && !this.broadcastMode && !broadcastAllowed()) {");
        assertThat(splitPane).contains("return de.kortty.policy.PolicyManager.effective().multiExecAllowed();");
        assertThat(splitPane).contains(
            "broadcastToggle.setDisable(rootCell.countWidgets() <= 1 || (!broadcastMode && !broadcastAllowed()));");
    }

    @Test
    void everyMultiExecEntryPointIsLockedWhileThePolicyDeniesIt() throws IOException {
        String window = source("src/main/java/de/kortty/ui/MainWindow.java");
        assertThat(window).contains("MultiExecMenuSupport.lockByPolicy(menu);");
        assertThat(window).contains("lockByPolicy(panes.broadcast());");
        assertThat(window).contains("multiExecItem.setDisable(!MultiExecCoordinator.shared().joinAllowed()");
        assertThat(source("src/main/java/de/kortty/ui/DashboardView.java"))
            .contains("multiExec.setDisable(!multiExec.isSelected() && !MultiExecCoordinator.shared().joinAllowed());");
        assertThat(source("src/main/java/de/kortty/ui/TerminalView.java"))
            .contains("include.setDisable(!multiExec.isMember(widget) && !multiExec.joinAllowed());");
    }

    @Test
    void theSettingsControlsCarryTheManagedLock() throws IOException {
        String dialog = source("src/main/java/de/kortty/ui/SettingsDialog.java");
        assertThat(dialog).contains("pasteWarningModeCombo, de.kortty.policy.ManagedSetting.PASTE_WARNING);");
        assertThat(dialog).contains("sessionRestoreModeCombo, de.kortty.policy.ManagedSetting.SESSION_RESTORE);");
        assertThat(dialog).contains("""
            de.kortty.policy.PolicyUiSupport.lockIf(osc52ClipboardWriteCheck,
                        !de.kortty.policy.PolicyManager.effective().osc52ClipboardWriteAllowed());""");
        // After setTooltip, never before: the lock replaces the tooltip with the managed hint.
        assertThat(dialog.indexOf("osc52ClipboardWriteCheck.setTooltip("))
            .isLessThan(dialog.indexOf("lockIf(osc52ClipboardWriteCheck,"));
        assertThat(dialog.indexOf("pasteWarningModeCombo.setTooltip("))
            .isLessThan(dialog.indexOf("pasteWarningModeCombo, de.kortty.policy.ManagedSetting.PASTE_WARNING"));
        assertThat(dialog.indexOf("sessionRestoreModeCombo.setTooltip("))
            .isLessThan(dialog.indexOf("sessionRestoreModeCombo, de.kortty.policy.ManagedSetting.SESSION_RESTORE"));
    }
}
