package de.kortty.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetAiResponseSupport;
import de.kortty.core.SnippetAiResponseSupport.CompletionSuggestion;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.DialogPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * End-to-end JavaFX smoke for the snippet editor's code completion: a real {@link SnippetEditDialog}
 * on a real Stage with the real Monaco WebView, fed by a stubbed AI provider that answers on its own
 * thread after a delay. It drives the Shift+TAB / menu list with local and AI rows, the verbatim
 * multi-line accept with its ↺ snapshot, Shift+TAB / TAB / Esc through the JavaFX event chain, a host
 * removed while the list is open, ghost text and its Esc dismissal, the cancel paths (heavy action,
 * hint-bar Cancel, dialog close) and snapshots the list and the ghost text to
 * {@code build/smoke/snippet-completion-*.png}. Run via the {@code snippetCompletionSmoke}
 * Gradle task. Exit 0 = OK, 1 = assertion failure, 2 = timeout.
 */
public final class SnippetCompletionSmoke {

    private static final String TEXT = "ARR=(a b c)\nNAME=x\nif true; then\n    for item in ";
    private static final String INDENTED_LINE = "    for item in ";
    private static final String FIRST_LOCAL_ROW = "\"${ARR[@]}\"";
    /** The stub's first candidate: one line, so the ghost commit leaves the caret at a line end. */
    private static final String AI_SINGLE_LINE = "\"$@\"; do echo \"$item\"; done";
    /** The stub's second candidate: line 2 indented by four spaces, which the accept must keep. */
    private static final String AI_MULTI_LINE = "\"${ARR[@]}\"; do\n    echo \"$item\"\ndone";
    /** The stub's third candidate repeats a local row and must be deduplicated away. */
    private static final String AI_DUPLICATE_OF_LOCAL = "\"${ARR[@]}\"";
    private static final List<CompletionSuggestion> DEFAULT_CANDIDATES = List.of(
        new CompletionSuggestion(AI_SINGLE_LINE, "one line"),
        new CompletionSuggestion(AI_MULTI_LINE, "loop body"),
        new CompletionSuggestion(AI_DUPLICATE_OF_LOCAL, "same as a local row"));
    /**
     * Ghost candidates for the dismiss check, all starting with {@link #DISMISS_PREFIX}: a typed
     * quote (which every default candidate starts with) is auto-closed by the shell language, and
     * that second character alone would keep a stale ghost away, fix or no fix.
     */
    private static final String DISMISS_PREFIX = "x";
    private static final List<CompletionSuggestion> DISMISS_CANDIDATES = List.of(
        new CompletionSuggestion("xargs -0 rm --", "dismiss one"),
        new CompletionSuggestion("xz --keep", "dismiss two"));
    private static final int LIST_CANDIDATES = 5;
    private static final int GHOST_CANDIDATES = 3;
    private static final double POLL_MS = 40;
    private static final Gson GSON = new Gson();

    private enum StubMode {
        QUICK(300),
        SLOW(3000),
        BLOCK(10_000);

        private final long delayMs;

        StubMode(long delayMs) {
            this.delayMs = delayMs;
        }
    }

    private record ProviderCall(SnippetEditDialog.CompletionRequest request, StubMode mode) {
    }

    private record RobotOutcome(String detail) {
    }

    private final AtomicReference<String> failure;
    private final CountDownLatch done;
    private final List<ProviderCall> providerCalls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger interruptedCalls = new AtomicInteger();
    private final AtomicReference<StubMode> stubMode = new AtomicReference<>(StubMode.QUICK);
    private final AtomicReference<List<CompletionSuggestion>> stubCandidates = new AtomicReference<>(DEFAULT_CANDIDATES);
    /** Host callbacks in arrival order (FX thread): requested:<id>, closed:<id>, accepted:<json>. */
    private final List<String> hostEvents = new ArrayList<>();
    private final List<JsonObject> acceptedEvents = new ArrayList<>();

    private SnippetEditDialog dialog;
    private DialogPane pane;
    private Stage stage;
    private MonacoEditorPane editor;
    private WebView webView;
    private CheckMenuItem autoCompleteItem;
    private PauseTransition ghostTimer;
    private Button cancelButton;
    private HBox hintBox;
    private Button toggleLastAiChangeButton;
    private boolean finished;
    private boolean started;

    private SnippetCompletionSmoke(AtomicReference<String> failure, CountDownLatch done) {
        this.failure = failure;
        this.done = done;
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<SnippetCompletionSmoke> smoke = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((t, e) ->
                failure.compareAndSet(null, "Uncaught on FX thread: " + e));
            // The dialog is the only window: without this the toolkit exits when it closes and the
            // pulses the harness still needs after a close (check 8, the cleanup pause) never run.
            Platform.setImplicitExit(false);
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                SnippetCompletionSmoke instance = new SnippetCompletionSmoke(failure, done);
                smoke.set(instance);
                instance.start();
            } catch (Throwable e) {
                failure.compareAndSet(null, "Smoke failed to start: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        if (!finished) {
            SnippetCompletionSmoke instance = smoke.get();
            System.err.println("snippetCompletionSmoke timed out"
                + (instance != null ? " | events=" + instance.hostEvents + " | providerCalls=" + instance.providerCalls.size() : "")
                + (failure.get() != null ? " | recorded failure: " + failure.get() : ""));
            dumpFxThread();
            Platform.exit();
            System.exit(2);
        }
        Platform.exit();
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("snippetCompletionSmoke OK");
    }

    /** Where the FX thread is stuck when the harness times out (a modal prompt, a nested loop, ...). */
    private static void dumpFxThread() {
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            String name = entry.getKey().getName();
            if (name.contains("JavaFX") || name.startsWith("snippet-ai")) {
                System.err.println("Thread " + name + " (" + entry.getKey().getState() + "):");
                for (StackTraceElement element : entry.getValue()) {
                    System.err.println("    at " + element);
                }
            }
        }
        System.err.flush();
    }

    // ------------------------------------------------------------------------------------------ harness

    private void start() {
        SnippetEditDialog.AiAssist assist = new SnippetEditDialog.AiAssist(
            null,
            null,
            null,
            null,
            null,
            null,
            this::completeStub,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            request -> new SnippetAiResponseSupport.MermaidDiagram("", ""),
            request -> {
                // Full code analysis: a real heavy action that must take the AI flow over. A null
                // result only sets the status line, so no further window opens.
                Thread.sleep(400);
                return null;
            },
            request -> new SnippetAiResponseSupport.SnippetSecurityFix("", "", List.of()),
            false,
            null);
        dialog = new SnippetEditDialog(new Snippet("completion-smoke.sh", TEXT, "bash"), List.of(), assist);
        editor = field(dialog, "contentArea", MonacoEditorPane.class);
        autoCompleteItem = field(dialog, "autoCompleteItem", CheckMenuItem.class);
        ghostTimer = field(dialog, "autoCompletionDelay", PauseTransition.class);
        cancelButton = field(dialog, "cancelSnippetAiActionButton", Button.class);
        hintBox = field(dialog, "snippetAiHintBox", HBox.class);
        toggleLastAiChangeButton = field(dialog, "toggleLastAiChangeButton", Button.class);
        wrapCompletionHost();

        dialog.show();
        pane = dialog.getDialogPane();
        stage = (Stage) pane.getScene().getWindow();
        webView = findWebView(editor);
        editor.readyProperty().addListener((obs, was, ready) -> {
            if (Boolean.TRUE.equals(ready)) {
                startWhenReady();
            }
        });
        if (editor.isReady()) {
            startWhenReady();
        }
    }

    private void startWhenReady() {
        if (started) {
            return;
        }
        started = true;
        // Let Monaco lay out once before the first command reaches it.
        after(800, this::check1OpenListWithLocalAndAiRows);
    }

    /** Records every callback and hands it to the dialog's own host (installed before the page boots). */
    private void wrapCompletionHost() {
        MonacoEditorPane.CompletionHost dialogHost = editor.getCompletionHost();
        if (dialogHost == null) {
            throw new AssertionError("SnippetEditDialog did not install a completion host on its editor");
        }
        editor.setCompletionHost(new MonacoEditorPane.CompletionHost() {
            @Override
            public void onCompletionRequested(long requestId, String requestJson) {
                hostEvents.add("requested:" + requestId);
                dialogHost.onCompletionRequested(requestId, requestJson);
            }

            @Override
            public void onCompletionListClosed(long requestId) {
                hostEvents.add("closed:" + requestId);
                dialogHost.onCompletionListClosed(requestId);
            }

            @Override
            public void onCompletionAccepted(String acceptedJson) {
                hostEvents.add("accepted:" + acceptedJson);
                acceptedEvents.add(JsonParser.parseString(acceptedJson).getAsJsonObject());
                dialogHost.onCompletionAccepted(acceptedJson);
            }
        });
    }

    /** The stubbed AI provider: answers on the task thread after the current mode's delay. */
    private List<CompletionSuggestion> completeStub(SnippetEditDialog.CompletionRequest request) throws Exception {
        StubMode mode = stubMode.get();
        providerCalls.add(new ProviderCall(request, mode));
        try {
            Thread.sleep(mode.delayMs);
        } catch (InterruptedException e) {
            interruptedCalls.incrementAndGet();
            throw e;
        }
        List<CompletionSuggestion> all = stubCandidates.get();
        int max = Math.max(1, request.maxCandidates());
        return List.copyOf(all.subList(0, Math.min(max, all.size())));
    }

    // ------------------------------------------------------------------------------------------ checks

    /** 1) Menu path opens the list: local rows resolve it, AI rows arrive later without a close. */
    private void check1OpenListWithLocalAndAiRows() {
        editor.moveTo(TEXT.length());
        int callsBefore = providerCalls.size();
        after(300, () -> {
            editor.triggerCompletionList();
            until("the list to open with local rows", () -> stateBool("listActive") && stateInt("local") >= 3, 3000, () -> {
                check("menu".equals(stateString("trigger")), "list trigger should be menu, was " + stateString("trigger"));
                int requestId = stateInt("requestId");
                int closedAtLocal = stateInt("closedCount");
                check(hostEvents.contains("requested:" + requestId), "host was not asked for request " + requestId);
                until("the suggest widget to render its rows", () -> firstSuggestRowLabel() != null, 2000, () -> {
                    String firstRow = firstSuggestRowLabel();
                    check(FIRST_LOCAL_ROW.equals(firstRow), "first local row should be " + FIRST_LOCAL_ROW + ", was " + firstRow);
                    check(providerCalls.size() == callsBefore + 1, "the list should start exactly one AI request, started "
                        + (providerCalls.size() - callsBefore));
                    ProviderCall call = providerCalls.get(callsBefore);
                    check(call.request().maxCandidates() == LIST_CANDIDATES, "list request should ask for " + LIST_CANDIDATES
                        + " candidates, asked for " + call.request().maxCandidates());
                    check(call.request().cursorOffset() == TEXT.length(), "list request caret should be " + TEXT.length()
                        + ", was " + call.request().cursorOffset());
                    System.out.println("check 1a: menu path opened the list with " + stateInt("local")
                        + " local rows, first row " + firstRow + ", AI asked for " + LIST_CANDIDATES + " candidates");
                    until("the AI rows to arrive", () -> stateInt("ai") >= 1, 3000, () -> {
                        check(stateBool("listActive") && stateInt("requestId") == requestId,
                            "the list must stay open while AI rows are merged");
                        check(stateInt("closedCount") == closedAtLocal,
                            "the list closed between the local and the AI push (closedCount " + closedAtLocal
                                + " -> " + stateInt("closedCount") + ")");
                        check(stateInt("ai") == 2, "expected 2 AI rows (the duplicate of a local row deduplicated), got "
                            + stateInt("ai"));
                        System.out.println("check 1b: " + stateInt("ai") + " AI rows merged into the open list, closedCount unchanged ("
                            + closedAtLocal + "), duplicate of a local row dropped");
                        after(200, () -> check2AcceptMultiLineAiRow(requestId));
                    });
                });
            });
        });
    }

    /** 2) Accepting the multi-line AI row inserts it verbatim, reports source=ai, arms ↺; undo restores. */
    private void check2AcceptMultiLineAiRow(int requestId) {
        int acceptedBefore = acceptedEvents.size();
        int closedBefore = stateInt("closedCount");
        String expected = TEXT + AI_MULTI_LINE;
        editor.triggerEditorCommand("selectLastSuggestion");
        // The AI rows sort last: with the last row selected the widget scrolls them into view.
        after(300, () -> {
            snapshot("snippet-completion-list.png");
            editor.triggerEditorCommand("acceptSelectedSuggestion");
            until("the multi-line AI row to be inserted", () -> expected.equals(editorText()), 2500, () -> {
                check(!stateBool("listActive"), "the list should close on accept");
                check(stateInt("closedCount") == closedBefore + 1, "accept should close the list exactly once");
                until("the accept callback", () -> acceptedEvents.size() > acceptedBefore, 1500, () -> {
                    JsonObject accepted = acceptedEvents.get(acceptedEvents.size() - 1);
                    check("ai".equals(string(accepted, "source")), "accepted source should be ai, was " + accepted);
                    check(AI_MULTI_LINE.equals(string(accepted, "insertedText")),
                        "accepted insertedText should be the multi-line row verbatim, was " + accepted);
                    check(accepted.get("start").getAsInt() == TEXT.length(), "accepted start should be " + TEXT.length()
                        + ", was " + accepted);
                    check(accepted.get("requestId").getAsInt() == requestId, "accepted requestId should be " + requestId
                        + ", was " + accepted);
                    int closedIndex = hostEvents.indexOf("closed:" + requestId);
                    int acceptedIndex = hostEvents.size() - 1;
                    check(closedIndex >= 0 && closedIndex < acceptedIndex, "closed must precede accepted: " + hostEvents);
                    until("the ↺ snapshot to be stored", () -> field(dialog, "lastAiChangeSnapshot", Object.class) != null, 1500, () -> {
                        check(!toggleLastAiChangeButton.isDisable(), "the ↺ button should be enabled after an AI accept");
                        System.out.println("check 2a: multi-line AI row inserted verbatim (line 2 kept its 4-space indent),"
                            + " accepted source=ai, ↺ snapshot stored");
                        undoUntil(TEXT, 2, undos -> {
                            check(field(dialog, "lastAiChangeSnapshot", Object.class) == null,
                                "an edit after the accept should clear the ↺ snapshot");
                            System.out.println("check 2b: undo restored the text (" + undos + " undo step"
                                + (undos == 1 ? "" : "s") + ")");
                            after(200, this::check3ShiftTabThroughJavaFx);
                        });
                    });
                });
            });
        });
    }

    /** 3) Shift+TAB as a JavaFX KeyEvent opens the list; Esc closes it; at a line start it outdents. */
    private void check3ShiftTabThroughJavaFx() {
        editor.moveTo(TEXT.length());
        webView.requestFocus();
        after(250, () -> {
            int requestBefore = stateInt("requestId");
            int closedBefore = stateInt("closedCount");
            check(!stateBool("listActive"), "no list should be open before Shift+TAB");
            KeyOutcome shiftTab = fireKey(KeyCode.TAB, true);
            until("Shift+TAB to open the list", () -> stateBool("listActive") && stateInt("requestId") > requestBefore, 3000, () -> {
                check("shiftTab".equals(stateString("trigger")), "list trigger should be shiftTab, was " + stateString("trigger"));
                int shiftTabRequest = stateInt("requestId");
                System.out.println("check 3a: Shift+TAB KeyEvent opened a new list session (request " + shiftTabRequest
                    + ", trigger shiftTab); KEY_PRESSED " + shiftTab);
                until("the AI rows of the Shift+TAB list", () -> stateInt("ai") >= 1, 3000, () -> {
                    KeyOutcome escape = fireKey(KeyCode.ESCAPE, false);
                    until("Esc to close the list", () -> !stateBool("listActive") && stateInt("closedCount") == closedBefore + 1
                        && hostEvents.contains("closed:" + shiftTabRequest), 2000, () -> {
                        check(stage.isShowing(), "Esc must close the list, not the dialog");
                        System.out.println("check 3b: Esc KeyEvent closed the list (closedCount " + closedBefore + " -> "
                            + stateInt("closedCount") + "), dialog still showing; KEY_PRESSED " + escape);
                        tryRobotShiftTab(outcome -> {
                            System.out.println("check 3c: Robot channel: " + outcome.detail());
                            after(200, this::check3OutdentAtLineStart);
                        });
                    });
                });
            });
        });
    }

    private void check3OutdentAtLineStart() {
        int lineStart = TEXT.indexOf(INDENTED_LINE);
        String outdented = TEXT.substring(0, lineStart) + INDENTED_LINE.substring(4);
        editor.selectRange(lineStart, lineStart);
        after(250, () -> {
            int requestBefore = stateInt("requestId");
            check(!stateBool("listActive"), "no list should be open before the outdent");
            KeyOutcome shiftTab = fireKey(KeyCode.TAB, true);
            until("Shift+TAB at the line start to outdent", () -> outdented.equals(editorText()), 2000, () -> {
                after(300, () -> {
                    check(!stateBool("listActive") && stateInt("requestId") == requestBefore,
                        "Shift+TAB at a line start must not open a list");
                    System.out.println("check 3d: Shift+TAB at the start of the indented line outdented it and opened no list;"
                        + " KEY_PRESSED " + shiftTab);
                    undoUntil(TEXT, 2, undos -> after(200, this::check4PlainTab));
                });
            });
        });
    }

    /** 4) Plain TAB accepts the highlighted row while the list is open, and indents otherwise. */
    private void check4PlainTab() {
        editor.moveTo(TEXT.length());
        after(250, () -> {
            int requestBefore = stateInt("requestId");
            fireKey(KeyCode.TAB, true);
            until("Shift+TAB to open the list for the TAB accept", () -> stateBool("listActive") && stateInt("requestId") > requestBefore, 3000, () -> {
                until("the AI rows before the TAB accept", () -> stateInt("ai") >= 1, 3000, () -> {
                    int acceptedBefore = acceptedEvents.size();
                    String expected = TEXT + FIRST_LOCAL_ROW;
                    KeyOutcome tab = fireKey(KeyCode.TAB, false);
                    until("TAB to accept the highlighted row", () -> expected.equals(editorText()), 2000, () -> {
                        check(!stateBool("listActive"), "the list should close after the TAB accept");
                        after(300, () -> {
                            check(acceptedEvents.size() == acceptedBefore, "a local row must not report an AI accept: " + acceptedEvents);
                            check(field(dialog, "lastAiChangeSnapshot", Object.class) == null, "a local row must not arm ↺");
                            System.out.println("check 4a: plain TAB with the list open accepted the highlighted row "
                                + FIRST_LOCAL_ROW + " (no AI accept reported); KEY_PRESSED " + tab);
                            undoUntil(TEXT, 2, undos -> after(300, this::check4TabIndents));
                        });
                    });
                });
            });
        });
    }

    private void check4TabIndents() {
        editor.moveTo(TEXT.length());
        after(250, () -> {
            check(!stateBool("listActive") && stateInt("ghostCached") == 0, "TAB indentation needs neither list nor ghost");
            int requestBefore = stateInt("requestId");
            KeyOutcome tab = fireKey(KeyCode.TAB, false);
            until("TAB to insert indentation", () -> {
                String text = editorText();
                return text.length() > TEXT.length() && text.startsWith(TEXT) && text.substring(TEXT.length()).isBlank();
            }, 2000, () -> {
                String inserted = editorText().substring(TEXT.length());
                check(!inserted.contains("\n"), "TAB must not insert a line break");
                check(!stateBool("listActive") && stateInt("requestId") == requestBefore, "plain TAB must not open a list");
                System.out.println("check 4b: plain TAB with no list inserted " + describeWhitespace(inserted)
                    + " and opened no list; KEY_PRESSED " + tab);
                undoUntil(TEXT, 2, undos -> after(200, this::check4DisableHostClosesList));
            });
        });
    }

    /**
     * 4c) Removing the completion host while a list is open closes the list on the page and reports the
     * close to the host being removed (its list state must not stay stuck); restoring the host makes
     * the next trigger open a list again.
     */
    private void check4DisableHostClosesList() {
        editor.moveTo(TEXT.length());
        after(250, () -> {
            editor.triggerCompletionList();
            until("the list before the host is removed", () -> stateBool("listActive") && stateInt("local") >= 3, 3000, () -> {
                until("the suggest widget to render before the host is removed", () -> firstSuggestRowLabel() != null, 2000, () -> {
                    int requestId = stateInt("requestId");
                    int closedBefore = stateInt("closedCount");
                    check(listSessionId() == requestId, "the dialog should track the open list, tracks " + listSessionId());
                    MonacoEditorPane.CompletionHost host = editor.getCompletionHost();
                    check(host != null, "the smoke's host wrapper should be installed");
                    editor.setCompletionHost(null);
                    check(!stateBool("enabled"), "setCompletionHost(null) should disable completion on the page");
                    until("the page to close the list when completion is disabled",
                        () -> !stateBool("listActive") && stateInt("closedCount") == closedBefore + 1, 2000, () -> {
                        until("the close to reach the removed host and the dialog",
                            () -> hostEvents.contains("closed:" + requestId) && listSessionId() == -1, 2000, () -> {
                            check(field(dialog, "completionTask", Object.class) == null,
                                "the list's AI request should end with the close");
                            System.out.println("check 4c: setCompletionHost(null) closed the open list (closedCount " + closedBefore
                                + " -> " + stateInt("closedCount") + "), the removed host received closed:" + requestId
                                + ", listSessionId back to -1");
                            editor.setCompletionHost(host);
                            check(stateBool("enabled"), "restoring the host should re-enable completion on the page");
                            editor.triggerCompletionList();
                            until("the list to open again after the host was restored",
                                () -> stateBool("listActive") && stateInt("requestId") > requestId && stateInt("local") >= 3, 3000, () -> {
                                int reopened = stateInt("requestId");
                                check(hostEvents.contains("requested:" + reopened), "the restored host should receive request " + reopened);
                                check(listSessionId() == reopened, "the dialog should track the reopened list");
                                fireKey(KeyCode.ESCAPE, false);
                                until("the reopened list to close", () -> !stateBool("listActive") && listSessionId() == -1, 2000, () -> {
                                    System.out.println("check 4d: the restored host opened list session " + reopened
                                        + " again; Esc closed it");
                                    after(200, this::check5GhostText);
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    /** 5) Auto AI Complete: ghost text after the pause, commit inserts it, reports source=ghost, arms ↺. */
    private void check5GhostText() {
        editor.moveTo(TEXT.length());
        setField(dialog, "autoCompletionWarningAccepted", true);
        int callsBefore = providerCalls.size();
        setAutoAiComplete(true);
        check(ghostTimer.getStatus() == Animation.Status.RUNNING, "enabling Auto AI Complete should arm the ghost timer");
        until("the ghost request to reach the provider", () -> providerCalls.size() > callsBefore, 3000, () -> {
            ProviderCall call = providerCalls.get(callsBefore);
            check(call.request().maxCandidates() == GHOST_CANDIDATES, "ghost request should ask for " + GHOST_CANDIDATES
                + " candidates, asked for " + call.request().maxCandidates());
            check(call.request().cursorOffset() == TEXT.length(), "ghost request caret should be " + TEXT.length()
                + ", was " + call.request().cursorOffset());
            check(TEXT.equals(call.request().fullContent()), "ghost request should carry the editor text");
            until("the ghost text to become visible", () -> stateBool("ghostVisible"), 3000, () -> {
                check(stateInt("ghostCached") == GHOST_CANDIDATES, "expected " + GHOST_CANDIDATES + " cached ghost candidates, got "
                    + stateInt("ghostCached"));
                // Monaco's model reports the ghost before the view paints it: wait for the decoration.
                until("the ghost decoration to render", () -> ghostDecorationText() != null, 2000, () -> {
                    String rendered = ghostDecorationText();
                    check(AI_SINGLE_LINE.equals(rendered), "rendered ghost text should be " + AI_SINGLE_LINE + ", was " + rendered);
                    System.out.println("check 5a: ghost request asked for " + GHOST_CANDIDATES + " candidates, ghost text visible ("
                        + stateInt("ghostCached") + " cached), rendered " + GSON.toJson(rendered));
                    after(300, this::check5CommitGhostText);
                });
            });
        });
    }

    private void check5CommitGhostText() {
        int acceptedBefore = acceptedEvents.size();
        String expected = TEXT + AI_SINGLE_LINE;
        snapshot("snippet-completion-ghost.png");
        editor.triggerEditorCommand("editor.action.inlineSuggest.commit");
        until("the ghost text to be committed", () -> expected.equals(editorText()), 2500, () -> {
            until("the ghost accept callback", () -> acceptedEvents.size() > acceptedBefore, 1500, () -> {
                JsonObject accepted = acceptedEvents.get(acceptedEvents.size() - 1);
                check("ghost".equals(string(accepted, "source")), "accepted source should be ghost, was " + accepted);
                check(AI_SINGLE_LINE.equals(string(accepted, "insertedText")),
                    "accepted insertedText should be the ghost text, was " + accepted);
                until("the ↺ snapshot after the ghost commit", () -> field(dialog, "lastAiChangeSnapshot", Object.class) != null, 1500, () -> {
                    check(!toggleLastAiChangeButton.isDisable(), "the ↺ button should be enabled after a ghost commit");
                    check(!stateBool("ghostVisible") && stateInt("ghostCached") == 0, "the ghost cache should be cleared on commit");
                    setAutoAiComplete(false);
                    check(ghostTimer.getStatus() == Animation.Status.STOPPED, "disabling Auto AI Complete should stop the ghost timer");
                    System.out.println("check 5b: commit inserted the ghost text, accepted source=ghost, ↺ snapshot stored");
                    after(300, this::check5DismissedGhostStaysHidden);
                });
            });
        });
    }

    /**
     * 5c) A ghost dismissed with Esc (editor.action.inlineSuggest.hide) stays away when the next
     * keystroke types a prefix of it — the page must drop its cache, not just hide the text — and a
     * fresh push from Java shows a ghost again.
     */
    private void check5DismissedGhostStaysHidden() {
        String text = editorText();
        editor.moveTo(text.length());
        stubCandidates.set(DISMISS_CANDIDATES);
        int callsBefore = providerCalls.size();
        setAutoAiComplete(true);
        until("the ghost for the dismiss check", () -> providerCalls.size() > callsBefore && stateBool("ghostVisible"), 3000, () -> {
            check(stateInt("ghostCached") == DISMISS_CANDIDATES.size(), "expected " + DISMISS_CANDIDATES.size()
                + " cached ghost candidates, got " + stateInt("ghostCached"));
            editor.triggerEditorCommand("editor.action.inlineSuggest.hide");
            until("the ghost to hide", () -> !stateBool("ghostVisible"), 2000, () -> {
                check(stateInt("ghostCached") == 0, "dismissing the ghost must drop the cached candidates, still "
                    + stateInt("ghostCached"));
                // Switch off without the menu handler (which would clear the page's cache itself): the
                // keystroke must not arm a fresh request while the stale cache is probed.
                autoCompleteItem.setSelected(false);
                int callsAtHide = providerCalls.size();
                typeCharacter(DISMISS_PREFIX);
                until("the typed prefix to reach the editor", () -> editorText().equals(text + DISMISS_PREFIX), 2000, () -> {
                    after(400, () -> {
                        check(!stateBool("ghostVisible") && stateInt("ghostCached") == 0,
                            "a dismissed ghost resurfaced after typing its prefix");
                        check(providerCalls.size() == callsAtHide, "no request may run while the switch is off");
                        System.out.println("check 5c: Esc dismissed the ghost and typing its prefix " + GSON.toJson(DISMISS_PREFIX)
                            + " did not bring it back (cache dropped)");
                        setAutoAiComplete(true);
                        until("a fresh ghost after the dismiss", () -> providerCalls.size() > callsAtHide && stateBool("ghostVisible"), 3000, () -> {
                            check(stateInt("ghostCached") == DISMISS_CANDIDATES.size(), "the fresh push should cache "
                                + DISMISS_CANDIDATES.size() + " candidates, cached " + stateInt("ghostCached"));
                            System.out.println("check 5d: a fresh Java push showed the ghost again after the dismiss");
                            setAutoAiComplete(false);
                            stubCandidates.set(DEFAULT_CANDIDATES);
                            undoUntil(text, 2, undos -> after(300, this::check6HeavyActionCancelsCompletion));
                        });
                    });
                });
            });
        });
    }

    /** 6) A heavy AI action (full code analysis) cancels the list's AI request and stops the ghost timer. */
    private void check6HeavyActionCancelsCompletion() {
        String text = editorText();
        editor.moveTo(text.length());
        stubMode.set(StubMode.SLOW);
        int callsBefore = providerCalls.size();
        int interruptedBefore = interruptedCalls.get();
        after(250, () -> {
            editor.triggerCompletionList();
            until("the list with a slow AI request", () -> stateBool("listActive") && providerCalls.size() > callsBefore, 3000, () -> {
                Task<?> completionTask = field(dialog, "completionTask", Task.class);
                check(completionTask != null, "the list should have a running completion task");
                int aiBefore = stateInt("ai");
                invoke(dialog, "runCodeReview", new Class<?>[0]);
                check(field(dialog, "completionTask", Object.class) == null, "full code analysis must cancel the completion task");
                check(completionTask.isCancelled(), "the superseded completion task should be cancelled");
                check(ghostTimer.getStatus() == Animation.Status.STOPPED, "full code analysis must stop the ghost timer");
                check(field(dialog, "snippetAiActionTask", Object.class) != null, "full code analysis should own the AI flow");
                until("the provider thread to see the interrupt", () -> interruptedCalls.get() > interruptedBefore, 1500, () -> {
                    after(700, () -> {
                        check(stateInt("ai") == aiBefore && stateInt("ai") == 0, "no AI rows may arrive after the cancel, got " + stateInt("ai"));
                        check(stateInt("ghostCached") == 0 && !stateBool("ghostVisible"), "no ghost may linger over a heavy action");
                        check(providerCalls.size() == callsBefore + 1, "no further completion request may start during the analysis");
                        System.out.println("check 6: full code analysis cancelled the list's AI request (task cancelled, provider interrupted,"
                            + " no AI rows arrived) and stopped the ghost timer");
                        stubMode.set(StubMode.QUICK);
                        fireKey(KeyCode.ESCAPE, false);
                        until("the list to close after the analysis", () -> !stateBool("listActive"), 2000, () ->
                            until("the analysis task to finish", () -> field(dialog, "snippetAiActionTask", Object.class) == null, 3000, () ->
                                after(200, this::check7CancelButtonDuringGhostRequest)));
                    });
                });
            });
        });
    }

    /** 7) The hint bar's Cancel stays enabled while a ghost request runs, also after typing, and cancels it. */
    private void check7CancelButtonDuringGhostRequest() {
        String text = editorText();
        editor.moveTo(text.length());
        stubMode.set(StubMode.SLOW);
        int callsBefore = providerCalls.size();
        setAutoAiComplete(true);
        check(ghostTimer.getStatus() == Animation.Status.RUNNING, "re-enabling Auto AI Complete should arm the ghost timer");
        until("the slow ghost request to start", () -> providerCalls.size() > callsBefore && field(dialog, "completionTask", Object.class) != null, 3000, () -> {
            Task<?> task = field(dialog, "completionTask", Task.class);
            until("the hint bar to show the running request", () -> hintBox.isVisible() && cancelButton.isVisible() && !cancelButton.isDisable(), 1500, () -> {
                typeCharacter("x");
                until("the typed character to reach the editor", () -> editorText().equals(text + "x"), 2000, () -> {
                    check(field(dialog, "completionTask", Object.class) == task, "typing must not replace the running ghost request");
                    check(hintBox.isVisible(), "the hint bar must stay visible while the request runs");
                    check(cancelButton.isVisible() && !cancelButton.isDisable(),
                        "the Cancel button must stay enabled after typing while a ghost request runs");
                    long cancelledAt = System.nanoTime();
                    check(!cancelButton.isDisabled(), "the Cancel button is effectively disabled (an ancestor is disabled)");
                    // A mouse click focuses the button on press before its action fires; fire() alone
                    // would leave the WebView as focus owner, where the dialog's Enter guard consumes
                    // every button action so the Enter key cannot trigger the default button.
                    cancelButton.requestFocus();
                    cancelButton.fire();
                    until("Cancel to stop the request and hide the hint", () -> describeCancelState(task),
                        () -> field(dialog, "completionTask", Object.class) == null && task.isCancelled() && !hintBox.isVisible(),
                        1000, () -> {
                        long millis = (System.nanoTime() - cancelledAt) / 1_000_000;
                        setAutoAiComplete(false);
                        check(ghostTimer.getStatus() == Animation.Status.STOPPED, "disabling Auto AI Complete should stop the ghost timer");
                        System.out.println("check 7: Cancel stayed enabled after typing during the ghost request and cancelled it;"
                            + " hint hidden after " + millis + " ms");
                        stubMode.set(StubMode.QUICK);
                        after(300, this::check8CloseWhileRequestRuns);
                    });
                });
            });
        });
    }

    /** 8) Closing the dialog while a request blocks in the provider raises nothing on the FX thread. */
    private void check8CloseWhileRequestRuns() {
        String text = editorText();
        editor.moveTo(text.length());
        stubMode.set(StubMode.BLOCK);
        int callsBefore = providerCalls.size();
        int interruptedBefore = interruptedCalls.get();
        after(250, () -> {
            editor.triggerCompletionList();
            until("the list with a blocking AI request", () -> stateBool("listActive") && providerCalls.size() > callsBefore, 3000, () -> {
                check(field(dialog, "completionTask", Object.class) != null, "the blocking request should be running");
                closeDialog();
                after(1500, () -> {
                    check(!stage.isShowing(), "the dialog should be closed");
                    check(failure.get() == null, "an exception reached the uncaught handler: " + failure.get());
                    check(interruptedCalls.get() > interruptedBefore, "closing the dialog should interrupt the blocked provider thread");
                    System.out.println("check 8: dialog closed while the provider blocked; request interrupted, no exception on the FX thread");
                    System.out.println("check 9: snapshots written to build/smoke/snippet-completion-list.png and snippet-completion-ghost.png");
                    finish();
                });
            });
        });
    }

    // ------------------------------------------------------------------------------------------ actions

    private record KeyOutcome(boolean consumedByWebView, boolean reachedScene) {
        @Override
        public String toString() {
            return "consumed by the WebView=" + consumedByWebView + ", reached the scene handler=" + reachedScene;
        }
    }

    /**
     * Fires KEY_PRESSED and KEY_RELEASED at the WebView node through the real JavaFX event chain
     * (window, scene, dialog pane, editor pane filters, then the WebView's own handler). JavaFX
     * re-sources (copies) the event at every node with handlers, so consumption cannot be read off
     * the fired instance; two witnesses report it instead: a {@code KeyEvent.ANY} handler on the
     * WebView registered after its own one (JavaFX dispatches the specific type before ANY, so a
     * KEY_PRESSED witness would run too early) and a scene-level handler that is only reached when
     * the event bubbled past the WebView unconsumed.
     */
    private KeyOutcome fireKey(KeyCode code, boolean shift) {
        Scene scene = webView.getScene();
        AtomicBoolean reachedScene = new AtomicBoolean();
        AtomicBoolean consumedByWebView = new AtomicBoolean();
        EventHandler<KeyEvent> sceneWitness = event -> {
            if (event.getEventType() == KeyEvent.KEY_PRESSED && event.getCode() == code) {
                reachedScene.set(true);
            }
        };
        EventHandler<KeyEvent> webViewWitness = event -> {
            if (event.getEventType() == KeyEvent.KEY_PRESSED && event.getCode() == code) {
                consumedByWebView.set(event.isConsumed());
            }
        };
        scene.addEventHandler(KeyEvent.ANY, sceneWitness);
        webView.addEventHandler(KeyEvent.ANY, webViewWitness);
        try {
            Event.fireEvent(webView, new KeyEvent(webView, webView, KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
            Event.fireEvent(webView, new KeyEvent(webView, webView, KeyEvent.KEY_RELEASED, "", "", code, shift, false, false, false));
            return new KeyOutcome(consumedByWebView.get(), reachedScene.get());
        } finally {
            scene.removeEventHandler(KeyEvent.ANY, sceneWitness);
            webView.removeEventHandler(KeyEvent.ANY, webViewWitness);
        }
    }

    /** Types one character the way the toolkit does: KEY_PRESSED, KEY_TYPED (with the character), KEY_RELEASED. */
    private void typeCharacter(String character) {
        KeyCode code = KeyCode.getKeyCode(character.toUpperCase());
        if (code == null) {
            code = KeyCode.UNDEFINED;
        }
        Event.fireEvent(webView, new KeyEvent(webView, webView, KeyEvent.KEY_PRESSED, "", character, code, false, false, false, false));
        Event.fireEvent(webView, new KeyEvent(webView, webView, KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false));
        Event.fireEvent(webView, new KeyEvent(webView, webView, KeyEvent.KEY_RELEASED, "", character, code, false, false, false, false));
    }

    /**
     * Second channel for Shift+TAB: real OS key events through {@link Robot}. They go to whichever
     * window the OS has in front, so they are only sent when this stage holds the focus; on macOS
     * without the Accessibility permission the events are silently dropped. Never fails the smoke.
     */
    private void tryRobotShiftTab(java.util.function.Consumer<RobotOutcome> then) {
        stage.toFront();
        stage.requestFocus();
        webView.requestFocus();
        after(400, () -> {
            if (!stage.isFocused()) {
                then.accept(new RobotOutcome("skipped, the smoke stage is not the focused window (Robot keys would reach another app)"));
                return;
            }
            int requestBefore = stateInt("requestId");
            Robot robot;
            try {
                robot = new Robot();
                robot.keyPress(KeyCode.SHIFT);
                robot.keyPress(KeyCode.TAB);
                robot.keyRelease(KeyCode.TAB);
                robot.keyRelease(KeyCode.SHIFT);
            } catch (RuntimeException e) {
                then.accept(new RobotOutcome("unavailable: " + e));
                return;
            }
            after(1000, () -> {
                boolean opened = stateBool("listActive") && stateInt("requestId") > requestBefore;
                if (!opened) {
                    then.accept(new RobotOutcome("Robot Shift+TAB did not reach Monaco (no new list session; on macOS this is the"
                        + " missing Accessibility permission for the JVM, not a product defect)"));
                    return;
                }
                String trigger = stateString("trigger");
                int robotRequest = stateInt("requestId");
                fireKey(KeyCode.ESCAPE, false);
                until("the Robot-opened list to close", () -> !stateBool("listActive"), 2000, () ->
                    then.accept(new RobotOutcome("Robot Shift+TAB opened list session " + robotRequest + " with trigger " + trigger
                        + (stage.isShowing() ? "; closed again with Esc" : "; but Esc closed the dialog"))));
            });
        });
    }

    /** Undoes until the text equals {@code expected}, at most {@code maxUndos} steps, then reports the count. */
    private void undoUntil(String expected, int maxUndos, java.util.function.IntConsumer then) {
        undoUntil(expected, maxUndos, 1, then);
    }

    private void undoUntil(String expected, int maxUndos, int attempt, java.util.function.IntConsumer then) {
        editor.undo();
        long deadline = System.nanoTime() + 1_200_000_000L;
        pollUntil("undo to restore the text", () -> expected.equals(editorText()), deadline, () -> then.accept(attempt), () -> {
            if (attempt >= maxUndos) {
                fail("undo did not restore the expected text after " + attempt + " step(s)");
                return;
            }
            undoUntil(expected, maxUndos, attempt + 1, then);
        });
    }

    /**
     * Closes the dialog the way its own OK/Cancel buttons do: {@code Dialog.close()} fires the
     * close request, whose unsaved-changes guard would otherwise open a modal prompt over the
     * edited text and block the harness.
     */
    private void closeDialog() {
        setField(dialog, "allowCloseWithoutUnsavedPrompt", true);
        dialog.close();
    }

    private String describeCancelState(Task<?> task) {
        return "[completionTask=" + field(dialog, "completionTask", Object.class) + ", sameTask="
            + (field(dialog, "completionTask", Object.class) == task) + ", task.isCancelled=" + task.isCancelled()
            + ", task.state=" + task.getState() + ", hintVisible=" + hintBox.isVisible() + ", hintManaged=" + hintBox.isManaged()
            + ", cancelVisible=" + cancelButton.isVisible() + ", cancelDisable=" + cancelButton.isDisable()
            + ", cancelDisabled=" + cancelButton.isDisabled() + ", timer=" + ghostTimer.getStatus()
            + ", snippetAiActionTask=" + field(dialog, "snippetAiActionTask", Object.class)
            + ", providerCalls=" + providerCalls.size() + ", interrupted=" + interruptedCalls.get() + "]";
    }

    /** Drives the real menu item: selection state plus its action handler (telemetry + toggle handler). */
    private void setAutoAiComplete(boolean on) {
        autoCompleteItem.setSelected(on);
        autoCompleteItem.getOnAction().handle(new ActionEvent(autoCompleteItem, autoCompleteItem));
        check(autoCompleteItem.isSelected() == on, "Auto AI Complete should be " + (on ? "on" : "off"));
    }

    // ------------------------------------------------------------------------------------------ state

    private String editorText() {
        editor.syncFromEditor();
        String text = editor.getText();
        return text != null ? text : "";
    }

    /** The dialog's id of the list it believes open, -1 for none. */
    private long listSessionId() {
        return field(dialog, "listSessionId", Long.class);
    }

    private JsonObject state() {
        String json = editor.completionDebugState();
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private String safeState() {
        try {
            return editor != null ? editor.completionDebugState() : "{}";
        } catch (RuntimeException e) {
            return "{unavailable: " + e + "}";
        }
    }

    private boolean stateBool(String key) {
        JsonElement value = state().get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private int stateInt(String key) {
        JsonElement value = state().get(key);
        return value != null && !value.isJsonNull() ? value.getAsInt() : -1;
    }

    private String stateString(String key) {
        JsonElement value = state().get(key);
        return value != null && !value.isJsonNull() ? value.getAsString() : null;
    }

    /** The label of the suggest widget's first row (data-index 0) from the page's DOM, or null while unrendered. */
    private String firstSuggestRowLabel() {
        Object rows = webView.getEngine().executeScript(
            "(function(){var rows=document.querySelectorAll('.suggest-widget .monaco-list-row');var out=[];"
                + "for(var i=0;i<rows.length;i++){var n=rows[i].querySelector('.label-name');"
                + "out.push({index:rows[i].getAttribute('data-index'),label:n?n.textContent:rows[i].textContent});}"
                + "return JSON.stringify(out);})()");
        if (!(rows instanceof String json)) {
            return null;
        }
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement element : array) {
            JsonObject row = element.getAsJsonObject();
            if ("0".equals(string(row, "index"))) {
                String label = string(row, "label");
                return label != null && !label.isBlank() ? label : null;
            }
        }
        return null;
    }

    /** The ghost text as Monaco painted it (its decoration nodes on the caret line), or null while unrendered. */
    private String ghostDecorationText() {
        Object text = webView.getEngine().executeScript(
            "(function(){var nodes=document.querySelectorAll('.ghost-text-decoration, .ghost-text');"
                + "if(!nodes.length)return null;var out='';for(var i=0;i<nodes.length;i++){out+=nodes[i].textContent;}"
                + "return out.replace(/\\u00a0/g,' ');})()");
        return text instanceof String value && !value.isBlank() ? value : null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() ? value.getAsString() : null;
    }

    private static String describeWhitespace(String inserted) {
        if (inserted.chars().allMatch(c -> c == ' ')) {
            return inserted.length() + " space" + (inserted.length() == 1 ? "" : "s");
        }
        if (inserted.chars().allMatch(c -> c == '\t')) {
            return inserted.length() + " tab" + (inserted.length() == 1 ? "" : "s");
        }
        return GSON.toJson(inserted);
    }

    // ------------------------------------------------------------------------------------------ sequencing

    private void after(double millis, Runnable action) {
        PauseTransition wait = new PauseTransition(Duration.millis(millis));
        wait.setOnFinished(event -> run(action));
        wait.play();
    }

    private void run(Runnable action) {
        if (finished) {
            return;
        }
        try {
            action.run();
        } catch (Throwable e) {
            fail(e instanceof AssertionError ? e.getMessage() : "exception: " + e);
        }
    }

    private void until(String what, BooleanSupplier condition, double timeoutMs, Runnable then) {
        until(what, () -> "", condition, timeoutMs, then);
    }

    private void until(String what, java.util.function.Supplier<String> detail, BooleanSupplier condition, double timeoutMs,
                       Runnable then) {
        long deadline = System.nanoTime() + (long) (timeoutMs * 1_000_000);
        pollUntil(what, condition, deadline, then, () -> fail("timed out waiting for " + what + " " + detail.get()));
    }

    private void pollUntil(String what, BooleanSupplier condition, long deadline, Runnable then, Runnable onTimeout) {
        run(() -> {
            if (condition.getAsBoolean()) {
                then.run();
                return;
            }
            if (System.nanoTime() > deadline) {
                onTimeout.run();
                return;
            }
            PauseTransition tick = new PauseTransition(Duration.millis(POLL_MS));
            tick.setOnFinished(event -> pollUntil(what, condition, deadline, then, onTimeout));
            tick.play();
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void fail(String message) {
        if (finished) {
            return;
        }
        failure.compareAndSet(null, message + " | state=" + safeState() + " | text=" + GSON.toJson(editor != null ? editor.getText() : null)
            + " | events=" + hostEvents + " | providerCalls=" + providerCalls.size() + " | interrupted=" + interruptedCalls.get());
        finish();
    }

    private void finish() {
        finished = true;
        try {
            if (dialog != null && stage != null && stage.isShowing()) {
                closeDialog();
            }
        } catch (RuntimeException e) {
            failure.compareAndSet(null, "closing the dialog failed: " + e);
        }
        // Give WebKit one pulse to release its native page before Platform.exit(), as the other smokes do.
        PauseTransition cleanup = new PauseTransition(Duration.seconds(1));
        cleanup.setOnFinished(event -> done.countDown());
        cleanup.play();
    }

    // ------------------------------------------------------------------------------------------ reflection & nodes

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(target.getClass().getSimpleName() + " is missing field " + name, e);
        }
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke " + name + " on " + target.getClass().getSimpleName(), e);
        }
    }

    private static WebView findWebView(Node root) {
        Node found = root.lookup(".web-view");
        if (found instanceof WebView view) {
            return view;
        }
        List<Node> all = new ArrayList<>();
        collect(root, all);
        for (Node node : all) {
            if (node instanceof WebView view) {
                return view;
            }
        }
        throw new AssertionError("MonacoEditorPane has no WebView child");
    }

    private static void collect(Node root, List<Node> out) {
        if (root == null) {
            return;
        }
        out.add(root);
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private void snapshot(String fileName) {
        try {
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.web("#1e1e1e"));
            WritableImage image = pane.snapshot(params, null);
            File out = new File("build/smoke/" + fileName);
            out.getParentFile().mkdirs();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
            System.out.println("Snapshot written: " + out.getAbsolutePath());
        } catch (Exception e) {
            throw new AssertionError("Snapshot " + fileName + " failed: " + e, e);
        }
    }
}
