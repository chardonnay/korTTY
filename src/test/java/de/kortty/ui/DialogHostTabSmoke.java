package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless-ish smoke harness for {@link DialogHostTab}: verifies the dialog-pane detach, button
 * interception (result converter + {@code DIALOG_HIDDEN} exactly once), the close-request veto on
 * tab close, {@code closeDialogOrHostTab()} from inside the hosted dialog, the tab title following
 * the dialog title, and the main-window close guard ({@link DialogHostTab#confirmClose()},
 * {@link HostedCloseGuards#confirmTabs}): a veto keeps everything, an approval disposes nothing.
 */
public final class DialogHostTabSmoke {

    /** Minimal stand-in with the same shape as the hosted management dialogs. */
    private static class ProbeDialog extends ThemeAwareDialog<Boolean> {
        final AtomicInteger hiddenCount = new AtomicInteger();
        final AtomicInteger converterOkCount = new AtomicInteger();
        boolean vetoClose;

        ProbeDialog() {
            setTitle("Probe");
            getDialogPane().setContent(new Label("probe content"));
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            setResultConverter(buttonType -> {
                if (buttonType == ButtonType.OK) {
                    converterOkCount.incrementAndGet();
                    return true;
                }
                return false;
            });
            addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> hiddenCount.incrementAndGet());
            setOnCloseRequest(event -> {
                if (vetoClose) {
                    event.consume();
                }
            });
        }

        void closeFromInside() {
            closeDialogOrHostTab();
        }
    }

    /** A hosted dialog with unsaved work: answers the main window's close guard as told. */
    private static final class GuardProbeDialog extends ProbeDialog implements HostedCloseGuard {
        final AtomicInteger asked = new AtomicInteger();
        boolean dirty = true;
        boolean approve;

        @Override
        public boolean confirmHostedClose() {
            asked.incrementAndGet();
            return !dirty || approve;
        }

        @Override
        public boolean needsCloseConfirmation() {
            return dirty;
        }
    }

    private DialogHostTabSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-dialog-host-tab-smoke");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Platform.startup(() -> run(failure, done));

        boolean finished = done.await(45, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("DialogHostTabSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("DialogHostTabSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("DialogHostTabSmoke OK");
        System.exit(0);
    }

    private static void run(AtomicReference<String> failure, CountDownLatch done) {
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setLanguage("en");
            LanguageManager.getInstance().initialize(settings);

            TabPane tabPane = new TabPane();
            Stage stage = new Stage();
            stage.setScene(new Scene(tabPane, 800, 600));
            stage.show();

            // 1) Detach: the pane must have been re-rooted out of the hidden dialog window's scene
            //    and become the tab's content inside the main scene.
            ProbeDialog okProbe = new ProbeDialog();
            check(okProbe.getDialogPane().getScene() != null,
                "expected the dialog pane to sit in the hidden dialog window's scene after construction");
            DialogHostTab okTab = DialogHostTab.host(tabPane, "probe", okProbe, null);
            check(okProbe.isHostedInTab(), "dialog must report hosted-in-tab");
            check(okTab.getContent() instanceof javafx.scene.layout.StackPane holder
                    && holder.getChildren().contains(okProbe.getDialogPane()),
                "tab content must be a holder wrapping the dialog pane");
            check(okProbe.getDialogPane().getScene() == stage.getScene(),
                "dialog pane must live in the main window's scene, got " + okProbe.getDialogPane().getScene());
            check(tabPane.getSelectionModel().getSelectedItem() == okTab, "host tab must be selected");

            // 2) OK button: converter runs, result is published, tab closes, DIALOG_HIDDEN fires once.
            Button okButton = (Button) okProbe.getDialogPane().lookupButton(ButtonType.OK);
            okButton.fireEvent(new ActionEvent(okButton, okButton));
            check(okProbe.converterOkCount.get() == 1, "OK converter must run exactly once");
            check(Boolean.TRUE.equals(okProbe.getResult()), "OK result must be published via setResult");
            check(!tabPane.getTabs().contains(okTab), "tab must be removed after OK");
            check(okProbe.hiddenCount.get() == 1,
                "DIALOG_HIDDEN must fire exactly once after OK, got " + okProbe.hiddenCount.get());

            // 3) Close-request veto: a consuming onCloseRequest keeps the tab open.
            ProbeDialog vetoProbe = new ProbeDialog();
            vetoProbe.vetoClose = true;
            DialogHostTab vetoTab = DialogHostTab.host(tabPane, "veto", vetoProbe, null);
            javafx.event.Event.fireEvent(vetoTab,
                new javafx.event.Event(vetoTab, vetoTab, javafx.scene.control.Tab.TAB_CLOSE_REQUEST_EVENT));
            check(vetoProbe.hiddenCount.get() == 0, "vetoed close must not fire DIALOG_HIDDEN");

            // 4) closeDialogOrHostTab from inside the dialog removes the tab and fires hidden once.
            vetoProbe.vetoClose = false;
            vetoProbe.closeFromInside();
            check(!tabPane.getTabs().contains(vetoTab), "tab must be removed by closeDialogOrHostTab");
            check(vetoProbe.hiddenCount.get() == 1, "programmatic close must fire DIALOG_HIDDEN once");
            check(Boolean.FALSE.equals(vetoProbe.getResult()) || vetoProbe.getResult() == null,
                "programmatic close must not fabricate an OK result");

            // 5) afterClosed callback runs after the hidden handlers.
            AtomicInteger closedCallback = new AtomicInteger();
            ProbeDialog cbProbe = new ProbeDialog();
            DialogHostTab cbTab = DialogHostTab.host(tabPane, null, cbProbe, closedCallback::incrementAndGet);
            check(cbTab.getToolId() == null, "null toolId must be preserved for multi-instance tools");
            Button cancelButton = (Button) cbProbe.getDialogPane().lookupButton(ButtonType.CANCEL);
            cancelButton.fireEvent(new ActionEvent(cancelButton, cancelButton));
            check(closedCallback.get() == 1, "afterClosed must run exactly once");
            check(cbProbe.hiddenCount.get() == 1, "cancel must fire DIALOG_HIDDEN once");

            // 6) The tab title follows the dialog title (an editor renames its tab, a workspace
            //    marks unsaved work).
            ProbeDialog titled = new ProbeDialog();
            DialogHostTab titledTab = DialogHostTab.host(tabPane, null, titled, null);
            check("Probe".equals(titledTab.getText()), "tab text must start as the dialog title: " + titledTab.getText());
            titled.setTitle("Renamed •");
            check("Renamed •".equals(titledTab.getText()), "tab text must follow the dialog title: " + titledTab.getText());
            titled.setTitle("");
            check(!titledTab.getText().isBlank(), "a blank dialog title must not blank the tab");
            check(titledTab.confirmClose(), "a dialog without a close guard never vetoes the main window");
            titledTab.closeProgrammatically();

            // 7) Close guard veto: the main window's Cmd+W / close all / window close ask first; a
            //    veto keeps the tab, its dialog and everything in it.
            GuardProbeDialog dirtyProbe = new GuardProbeDialog();
            DialogHostTab dirtyTab = DialogHostTab.host(tabPane, "dirty", dirtyProbe, null);
            GuardProbeDialog cleanProbe = new GuardProbeDialog();
            cleanProbe.dirty = false;
            DialogHostTab cleanTab = DialogHostTab.host(tabPane, "clean", cleanProbe, null);
            check(tabPane.getSelectionModel().getSelectedItem() == cleanTab, "the last hosted tab starts selected");
            check(dirtyTab.needsCloseConfirmation() && !cleanTab.needsCloseConfirmation(),
                "only the dirty dialog needs to be asked");
            check(!dirtyTab.confirmClose(), "a vetoing guard must veto confirmClose");
            check(tabPane.getTabs().contains(dirtyTab) && dirtyProbe.hiddenCount.get() == 0,
                "a veto must not close or dispose anything");
            dirtyProbe.asked.set(0);
            check(!HostedCloseGuards.confirmTabs(tabPane.getTabs(), tab -> tabPane.getSelectionModel().select(tab)),
                "close all must stop at the veto");
            check(dirtyProbe.asked.get() == 1 && cleanProbe.asked.get() == 0,
                "only the dialog with unsaved work is asked (dirty=" + dirtyProbe.asked.get()
                    + ", clean=" + cleanProbe.asked.get() + ")");
            check(tabPane.getSelectionModel().getSelectedItem() == dirtyTab,
                "the tab that asks must be brought to the front first");
            check(tabPane.getTabs().contains(dirtyTab) && tabPane.getTabs().contains(cleanTab),
                "a vetoed close all keeps every tab");

            // 8) Approval has no side effects: the caller disposes only after all its prompts passed.
            dirtyProbe.approve = true;
            check(dirtyTab.confirmClose(), "an approving guard must allow the close");
            check(HostedCloseGuards.confirmTabs(tabPane.getTabs(), null), "all guards approve");
            check(tabPane.getTabs().contains(dirtyTab) && dirtyProbe.hiddenCount.get() == 0,
                "an approval alone must not close or dispose the tab");
            dirtyTab.closeProgrammatically();
            cleanTab.closeProgrammatically();
            check(dirtyProbe.hiddenCount.get() == 1 && cleanProbe.hiddenCount.get() == 1,
                "disposing after the approval runs the hidden lifecycle once");
            check(dirtyTab.confirmClose(), "a closed tab never vetoes");

            stage.hide();
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
