package de.kortty.policy;

import de.kortty.ui.I18n;
import javafx.scene.control.Alert;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;

/**
 * JavaFX helpers for surfacing policy state: locking managed controls with a
 * "managed by your organization" hint, and the startup/blocked dialogs. Locked controls are
 * disabled visibly instead of silently reverting — the user should understand why a setting cannot
 * change.
 */
public final class PolicyUiSupport {

    private PolicyUiSupport() {
    }

    /** The tooltip/hint text, including the organization name when the policy declares one. */
    public static String managedByOrganizationText() {
        String organization = PolicyManager.effective().organization()
            .map(name -> " (" + name + ")")
            .orElse("");
        return I18n.get("policy.managedByOrg", organization);
    }

    /**
     * Disables {@code control} with the managed-by-organization tooltip when {@code setting} is
     * policy-managed. Returns true when the control was locked.
     */
    public static boolean lockIfManaged(Control control, ManagedSetting setting) {
        if (!PolicyManager.effective().isManaged(setting)) {
            return false;
        }
        control.setDisable(true);
        control.setTooltip(new Tooltip(managedByOrganizationText()));
        return true;
    }

    /** Menu-item variant of {@link #lockIfManaged(Control, ManagedSetting)}. */
    public static boolean lockIfManaged(MenuItem item, ManagedSetting setting) {
        if (!PolicyManager.effective().isManaged(setting)) {
            return false;
        }
        item.setDisable(true);
        return true;
    }

    /** A banner label for settings tabs that contain locked controls. */
    public static Label managedTabBanner() {
        Label banner = new Label("🔒 " + I18n.get("policy.settings.tabBanner"));
        banner.setWrapText(true);
        banner.getStyleClass().add("policy-managed-banner");
        banner.setStyle("-fx-font-size: 11px; -fx-text-fill: #8a6d3b; -fx-background-color: #fcf8e3; "
            + "-fx-padding: 6 10 6 10; -fx-background-radius: 4;");
        return banner;
    }

    /** True when any of the given settings is policy-managed (banner condition for a tab). */
    public static boolean anyManaged(ManagedSetting... settings) {
        EffectivePolicy policy = PolicyManager.effective();
        for (ManagedSetting setting : settings) {
            if (policy.isManaged(setting)) {
                return true;
            }
        }
        return false;
    }

    /** Modal dialog shown when a connection is blocked by the server policy. */
    public static void showBlockedServerDialog(String hostAndPort) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.get("policy.server.blocked.title"));
        alert.setHeaderText(I18n.get("policy.server.blocked.title"));
        alert.setContentText(I18n.get("policy.server.blocked.message", hostAndPort)
            + "\n" + managedByOrganizationText());
        alert.showAndWait();
    }

    /** Startup dialog for an invalid policy file (lockdown active). */
    public static void showMalformedPolicyDialog(PolicyLoadResult result) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("policy.startup.malformed.title"));
        alert.setHeaderText(I18n.get("policy.startup.malformed.title"));
        String details = String.join("\n", result.errors());
        alert.setContentText(I18n.get("policy.startup.malformed.message", result.source(), details)
            + "\n\n" + I18n.get("policy.startup.lockdownHint"));
        alert.setResizable(true);
        alert.showAndWait();
    }
}
