package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.ui.TerminalAction;
import com.sithtermfx.ui.split.BroadcastTargets;
import com.sithtermfx.ui.split.BroadcastTargets.KeyRoute;
import de.kortty.shellintegration.PromptNavigator.Direction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import javafx.scene.input.KeyCode;
import org.testng.annotations.Test;

/**
 * Previous Prompt and Next Prompt in a pane whose keys go to other panes too, through a tab's
 * broadcast mode or through multi-exec into panes of other tabs and windows. While the pane has
 * prompt marks on its normal screen the key jumps in that pane and reaches no program at all, neither
 * the pane's own nor a broadcast or multi-exec target's; without marks, and on the alternate screen
 * of a full-screen program, it goes to the pane's program and to every target, like any other arrow
 * key, exactly as before shell integration.
 *
 * <p>Both mirror paths share one decision: {@code TerminalSplitPane.routeKeyPressed} asks
 * {@link BroadcastTargets#routeOf} with the pane's first matching key action, which is korTTY's
 * prompt action ({@code KorttyTermWidget} puts its actions in front of SithTermFX's), and only a key
 * that does not stay local is sent and handed to {@code broadcastToOthers}, whose targets are the
 * tab's panes in broadcast mode followed by the multi-exec members. The real prompt actions and the
 * real target choice are exercised here; that the split pane is wired that way is read from its
 * source, because a live split pane needs a JavaFX toolkit (the headed
 * {@code shellIntegrationPromptSmoke} drives a real pane's jump).
 *
 * <p>What a target gets is encoded for it: on Windows and Linux the key is Ctrl+Shift+Up/Down and
 * reaches every target as xterm's {@code ESC [ 1 ; 6 A} / {@code B}; on macOS it is Cmd+Shift+Up/Down,
 * and a Cmd chord is a shortcut that korTTY never types into another pane, with or without marks.
 */
class PromptKeyMirrorRouteTest {

    private static final Path SPLIT_PANE = Path.of("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

    /** A pane stand-in; panes are told apart by reference. */
    private record Pane(String name) {
    }

    private final Pane source = new Pane("tab 1, pane 1");
    private final Pane tabNeighbour = new Pane("tab 1, pane 2");
    private final Pane otherTab = new Pane("tab 2, pane 1");
    private final Pane otherWindow = new Pane("window 2, tab 1, pane 1");

    @Test
    void withPromptMarksAJumpStaysInThePaneAndReachesNeitherBroadcastNorMultiExecTargets() {
        AtomicBoolean marks = new AtomicBoolean(true);
        List<Direction> jumps = new ArrayList<>();
        List<TerminalAction> actions = promptActions(marks, jumps);

        for (int key = 0; key < actions.size(); key++) {
            TerminalAction action = actions.get(key);
            KeyRoute route = BroadcastTargets.routeOf(false, () -> action.isEnabled(null));
            assertThat(route).isEqualTo(KeyRoute.LOCAL_ACTION);
            assertWithMessage("broadcast mode and multi-exec together")
                .that(receivers(route, List.of(source, tabNeighbour), List.of(otherTab, otherWindow))).isEmpty();
            assertWithMessage("multi-exec alone, members in other tabs and windows")
                .that(receivers(route, List.of(), List.of(otherTab, otherWindow))).isEmpty();
            assertWithMessage("broadcast mode alone")
                .that(receivers(route, List.of(source, tabNeighbour), List.of())).isEmpty();
            assertThat(action.actionPerformed(null)).isTrue();
        }
        assertWithMessage("the jump runs in the pane the key was pressed in")
            .that(jumps).containsExactly(Direction.PREVIOUS, Direction.NEXT).inOrder();
    }

    @Test
    void withoutMarksTheKeyGoesToTheProgramAndIsMirroredAsBefore() {
        AtomicBoolean marks = new AtomicBoolean(false);
        List<Direction> jumps = new ArrayList<>();
        for (TerminalAction action : promptActions(marks, jumps)) {
            KeyRoute route = BroadcastTargets.routeOf(false, () -> action.isEnabled(null));
            assertThat(route).isEqualTo(KeyRoute.SEND_AND_MIRROR);
            assertWithMessage("the pane's program first, then the tab's panes, then the multi-exec members")
                .that(receivers(route, List.of(source, tabNeighbour), List.of(otherTab, otherWindow)))
                .containsExactly(source, tabNeighbour, otherTab, otherWindow).inOrder();
            assertWithMessage("multi-exec alone")
                .that(receivers(route, List.of(), List.of(otherTab, otherWindow)))
                .containsExactly(source, otherTab, otherWindow).inOrder();
            assertWithMessage("a pane in both lists gets the key once, among the tab's panes")
                .that(receivers(route, List.of(source, tabNeighbour), List.of(tabNeighbour, otherTab)))
                .containsExactly(source, tabNeighbour, otherTab).inOrder();
        }
        assertWithMessage("no jump without marks").that(jumps).isEmpty();
    }

    @Test
    void onTheAlternateScreenTheKeyIsMirroredEvenWithMarks() {
        AtomicBoolean marks = new AtomicBoolean(true);
        for (TerminalAction action : promptActions(marks, new ArrayList<>())) {
            KeyRoute route = BroadcastTargets.routeOf(true, () -> action.isEnabled(null));
            assertThat(route).isEqualTo(KeyRoute.SEND_AND_MIRROR);
            assertThat(receivers(route, List.of(), List.of(otherTab)))
                .containsExactly(source, otherTab).inOrder();
        }
    }

    @Test
    void withoutMarksEveryTargetGetsTheChordAsXtermSendsItAndOnMacOsNoTargetGetsTheCmdChord() {
        IntFunction<byte[]> shell = arrows("\u001B[A", "\u001B[B");
        IntFunction<byte[]> vim = arrows("\u001BOA", "\u001BOB");
        for (IntFunction<byte[]> target : List.of(shell, vim)) {
            assertWithMessage("Ctrl+Shift+Up on Windows and Linux, in normal and application cursor mode")
                .that(TerminalNavigationKeys.encode(KeyCode.UP, true, true, false, false, false, target))
                .isEqualTo(ascii("\u001B[1;6A"));
            assertThat(TerminalNavigationKeys.encode(KeyCode.DOWN, true, true, false, false, false, target))
                .isEqualTo(ascii("\u001B[1;6B"));
            assertWithMessage("Cmd+Shift+Up on macOS is a shortcut, never input for another pane")
                .that(TerminalNavigationKeys.encode(KeyCode.UP, true, false, false, true, true, target)).isNull();
            assertThat(TerminalNavigationKeys.encode(KeyCode.DOWN, true, false, false, true, true, target)).isNull();
        }
    }

    @Test
    void theDecisionFollowsTheMarksFromKeyToKey() {
        AtomicBoolean marks = new AtomicBoolean(false);
        TerminalAction previous = promptActions(marks, new ArrayList<>()).getFirst();
        assertThat(BroadcastTargets.routeOf(false, () -> previous.isEnabled(null))).isEqualTo(KeyRoute.SEND_AND_MIRROR);
        marks.set(true);
        assertWithMessage("the first prompt mark arrives: the same action now keeps the key local")
            .that(BroadcastTargets.routeOf(false, () -> previous.isEnabled(null))).isEqualTo(KeyRoute.LOCAL_ACTION);
        marks.set(false);
        assertWithMessage("shell integration switched off, or a full-screen program started")
            .that(BroadcastTargets.routeOf(false, () -> previous.isEnabled(null))).isEqualTo(KeyRoute.SEND_AND_MIRROR);
    }

    @Test
    void theSplitPaneDecidesOnceBeforeSendingAndBothMirrorPathsFollow() throws IOException {
        String splitPane = Files.readString(SPLIT_PANE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String route = body(splitPane, "private void routeKeyPressed(@NotNull SithTermFxWidget widget, @NotNull KeyEvent event) {");
        int local = route.indexOf("performsLocalScrollAction(widget, event)");
        int send = route.indexOf("sendToPane(widget, bytes)");
        int mirror = route.indexOf("broadcastToOthers(widget, target -> encodeKeyFor(target, event));");
        assertThat(local).isAtLeast(0);
        assertWithMessage("a key that stays local is neither sent nor mirrored").that(send).isGreaterThan(local);
        assertThat(mirror).isGreaterThan(send);

        String decision = body(splitPane, "private static boolean performsLocalScrollAction(");
        assertWithMessage("the first matching key action of the pane decides, korTTY's prompt actions first")
            .that(decision).contains("TerminalAction action = alternateScreen ? null : firstMatchingAction(panel, event);");
        assertThat(decision).contains("BooleanSupplier paneAction = action != null ? () -> action.isEnabled(event) : null;");

        String targets = body(splitPane, "private @NotNull List<SithTermFxWidget> mirrorTargetsOf(@NotNull SithTermFxWidget source) {");
        assertWithMessage("broadcast mode's panes and the multi-exec members are the targets of the same key")
            .that(targets).contains("return BroadcastTargets.resolve(source, broadcastMode ? getAllWidgets() : List.of(),\n"
                + "            mirrorMembersBesides(source), TerminalSplitPane::isConnected, this::acceptedByOwner);");

        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertWithMessage("multi-exec is every tab's input mirror")
            .that(view).contains("splitPane.setInputMirror(MultiExecCoordinator.shared());");
        String controller = Files.readString(Path.of("src/main/java/de/kortty/ui/ShellIntegrationController.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertWithMessage("the pane's prompt actions are enabled exactly while it can navigate")
            .that(controller).contains("korttyWidget.setLeadingTerminalActions(promptActions(previousPromptKey, nextPromptKey,\n"
                + "                () -> canNavigate(widget), direction -> jump(widget, direction)));");
    }

    /** A terminal's unmodified Up and Down, by SithTermFX key code. */
    private static IntFunction<byte[]> arrows(String up, String down) {
        return vk -> vk == KeyCode.UP.getCode() ? ascii(up) : vk == KeyCode.DOWN.getCode() ? ascii(down) : null;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static List<TerminalAction> promptActions(AtomicBoolean marks, List<Direction> jumps) {
        return ShellIntegrationController.promptActions(MainWindow.previousPromptAccelerator(),
            MainWindow.nextPromptAccelerator(), marks::get, jumps::add);
    }

    /**
     * Which panes get the key, as {@code TerminalSplitPane.routeKeyPressed} sends it: none when it
     * stays local, else the pane's own program and then every connected, accepted target.
     */
    private List<Pane> receivers(KeyRoute route, List<Pane> broadcastPanes, List<Pane> multiExecMembers) {
        if (route == KeyRoute.LOCAL_ACTION) {
            return List.of();
        }
        List<Pane> receivers = new ArrayList<>();
        receivers.add(source);
        receivers.addAll(BroadcastTargets.resolve(source, broadcastPanes, multiExecMembers, pane -> true, pane -> true));
        return receivers;
    }

    /** The text of the method whose declaration starts with {@code signature}, up to its closing brace. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing " + signature).that(start).isAtLeast(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}\n", start);
        assertWithMessage("no end of " + signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }
}
