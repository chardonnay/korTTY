package de.kortty.ui;

import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentNotificationCoordinator;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.CodingAgentTestHarness;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Spinner;
import javafx.scene.paint.Color;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real SettingsDialog and notification coordinator. The harness's pane, focus and clock, and the
 * recording notification sink are explicit test doubles; no OS notification is sent.
 */
public final class MessagingSettingsSmoke {
    private MessagingSettingsSmoke() { }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("kortty-messaging-smoke");
        System.setProperty("user.home", home.toString());
        Locale.setDefault(Locale.ENGLISH);
        Path output = Path.of(args.length > 0 ? args[0] : "build/smoke").toAbsolutePath();
        Files.createDirectories(output);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.startup(() -> {
            try {
                run(home, output);
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("Messaging settings smoke timed out");
        }
        Platform.runLater(Platform::exit);
        if (failure.get() != null) {
            failure.get().printStackTrace();
            System.exit(1);
        }
        System.out.println("MessagingSettingsSmoke OK");
        System.exit(0);
    }

    private static void run(Path home, Path output) throws Exception {
        GlobalSettingsManager manager = new GlobalSettingsManager(home.resolve(".kortty"));
        GlobalSettings settings = manager.getSettings();
        settings.setLanguage("en");
        LanguageManager.getInstance().initialize(settings);
        SettingsDialog dialog = new SettingsDialog(null, null, new ConfigurationManager(home), settings,
            new CredentialManager(home), new GPGKeyManager(home));
        DialogThemeHelper.applyTheme(dialog);
        String css = ThemeCssSupport.getDynamicStylesheetUrl(ThemeCssSupport.resolveThemeColors(settings, null));
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
        TabPane tabs = field(dialog, "mainTabPane", TabPane.class);
        Tab messaging = tabs.getTabs().stream().filter(tab -> tab.getUserData() == SettingsSection.MESSAGING)
            .findFirst().orElseThrow();
        require(LazyTabContent.isPending(messaging), "Messaging is initially deferred");
        dialog.selectSection(SettingsSection.MESSAGING);
        require(tabs.getSelectionModel().getSelectedItem() == messaging, "stable section selection opens Messaging");
        CheckBox coding = field(dialog, "codingAgentNotificationsCheck", CheckBox.class);
        require(coding.isSelected(), "coding-agent notifications default to on");
        require(coding.getParent() == messaging.getContent(), "the coding-agent switch belongs to Messaging");
        CheckBox shell = field(dialog, "shellIntegrationCheck", CheckBox.class);
        CheckBox command = field(dialog, "commandFinishedNotificationsCheck", CheckBox.class);
        Spinner<?> seconds = field(dialog, "commandFinishedSecondsSpinner", Spinner.class);
        shell.setSelected(false);
        require(command.isDisabled() && seconds.isDisabled(), "shell-integration dependency works across pages");
        shell.setSelected(true);
        require(!command.isDisabled() && !seconds.isDisabled(), "shell integration restores command controls");

        dialog.getDialogPane().setPrefSize(1120, 1000);
        dialog.show();
        var scene = dialog.getDialogPane().getScene();
        scene.snapshot(null);
        CodingAgentSmokeFixture.writePng(CodingAgentSmokeFixture.snapshot(scene, 2, Color.web("#1e1e1e")),
            output.resolve("messaging-settings.png").toFile());
        Method apply = SettingsDialog.class.getDeclaredMethod("applySettings");
        apply.setAccessible(true);
        try (CodingAgentTestHarness harness = new CodingAgentTestHarness()) {
            AtomicInteger deliveries = new AtomicInteger();
            CodingAgentNotificationCoordinator coordinator = new CodingAgentNotificationCoordinator(harness.registry(),
                harness.focus(), settings::isCodingAgentNotificationsEnabled,
                (entry, state, focused) -> deliveries.incrementAndGet(), harness::nowMillis);
            try (AutoCloseable listener = harness.registry().addListener(coordinator)) {
                coding.fire();
                require(Boolean.TRUE.equals(apply.invoke(dialog)), "saving Messaging succeeds");
                require(!settings.isCodingAgentNotificationsEnabled(), "the switch disables the stored setting");
                require(settings.isCodingAgentDetectionEnabled() && settings.isCodingAgentPanelAutoVisibility()
                        && settings.isCodingAgentAppBadgeEnabled(),
                    "muting messages keeps detection, the sidebar and the badge enabled");
                require(settings.isRemoteTerminalNotificationsEnabled() && settings.isAiRunToastsEnabled(),
                    "other notification choices stay independent");
                manager.save();
                GlobalSettingsManager restored = new GlobalSettingsManager(home.resolve(".kortty"));
                restored.load();
                require(!restored.getSettings().isCodingAgentNotificationsEnabled(), "the disabled value survives reload");
                harness.addAgent("demo", "muted", CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED,
                    "Do you want to proceed? (y/n)");
                harness.advance(CodingAgentNotificationCoordinator.SETTLE_MILLIS);
                coordinator.tick();
                require(deliveries.get() == 0, "a blocked agent produces no message while disabled");
                coding.fire();
                require(Boolean.TRUE.equals(apply.invoke(dialog)), "enabling Messaging succeeds");
                harness.addAgent("demo", "enabled", CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED,
                    "Do you want to proceed? (y/n)");
                harness.advance(CodingAgentNotificationCoordinator.SETTLE_MILLIS);
                coordinator.tick();
                require(deliveries.get() == 1, "the switch immediately enables notifications for new transitions");
            }
        } finally {
            dialog.close();
        }
    }

    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
