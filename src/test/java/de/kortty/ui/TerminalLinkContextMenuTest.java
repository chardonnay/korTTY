package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.ui.TerminalLinkContextMenu.Entry;
import de.kortty.ui.TerminalLinkResolver.Link;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.AUTO;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.OSC8;
import static de.kortty.ui.TerminalLinkContextMenu.COPY_LINK_KEY;
import static de.kortty.ui.TerminalLinkContextMenu.COPY_PATH_KEY;
import static de.kortty.ui.TerminalLinkContextMenu.OPEN_FILE_KEY;
import static de.kortty.ui.TerminalLinkContextMenu.OPEN_LINK_KEY;
import static de.kortty.ui.TerminalLinkResolver.WEB_LINK_KINDS;
import static org.testng.Assert.assertThrows;

/**
 * A right-click on a terminal link offers Open Link and Copy Link Address, a way to a link without
 * holding Cmd or Ctrl. These cases pin when the two entries appear (only for a link korTTY opens),
 * that Open Link goes the Cmd/Ctrl+click way, what Copy Link Address copies (an OSC 8 link's real
 * target, never its text; a plain-text address as printed), and that the menu acts on the link
 * under the right-button press. Toolkit-free: links come from fakes and from a real emulator buffer.
 */
public class TerminalLinkContextMenuTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties", "messages_de.properties", "messages_it.properties", "messages_es.properties",
        "messages_pt.properties", "messages_fr.properties", "messages_hr.properties", "messages_nl.properties");

    private static Link link(TerminalLinkClickPolicy.HitKind kind, String target, String text) {
        return new Link(kind, target == null ? null : URI.create(target), text, new Point(0, 0), new Point(3, 0));
    }

    private static List<String> keys(List<Entry> entries) {
        return entries.stream().map(Entry::i18nKey).toList();
    }

    @Test
    public void noLinkMeansNoEntries() {
        assertThat(TerminalLinkContextMenu.entries(null, link -> { }, text -> { })).isEmpty();
    }

    @Test
    public void aLinkKorttyDoesNotOpenGetsNoEntries() {
        // A refused target, an OSC 8 link of another origin, a kind korTTY opens in no browser.
        Link refused = link(AUTO, null, "https://example.com/" + "a".repeat(10));
        Link foreignOsc8 = link(OSC8, null, "docs");

        assertThat(TerminalLinkContextMenu.entries(refused, link -> { }, text -> { })).isEmpty();
        assertThat(TerminalLinkContextMenu.entries(foreignOsc8, link -> { }, text -> { })).isEmpty();
    }

    @Test
    public void aLinkWithATargetGetsOpenThenCopy() {
        List<Link> opened = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        Link osc8 = link(OSC8, "https://docs.example.com/guide", "the guide");

        List<Entry> entries = TerminalLinkContextMenu.entries(osc8, opened::add, copied::add);

        assertThat(keys(entries)).containsExactly(OPEN_LINK_KEY, COPY_LINK_KEY).inOrder();
        assertThat(OPEN_LINK_KEY).isEqualTo("terminal.contextMenu.openLink");
        assertThat(COPY_LINK_KEY).isEqualTo("terminal.contextMenu.copyLink");
        // Building the menu neither opens nor copies anything.
        assertThat(opened).isEmpty();
        assertThat(copied).isEmpty();

        entries.get(0).action().run();
        assertThat(opened).containsExactly(osc8);
        assertThat(copied).isEmpty();

        entries.get(1).action().run();
        assertThat(copied).containsExactly("https://docs.example.com/guide");
        assertThat(opened).hasSize(1);
    }

    @Test
    public void openLinkHandsOverTheLinkWithItsTextForTheHostQuestion() {
        // The host-mismatch question needs the OSC 8 link's visible text, so the whole link goes on.
        List<Link> opened = new ArrayList<>();
        Link deceptive = link(OSC8, "https://evil.example/login", "https://example.com/login");

        TerminalLinkContextMenu.entries(deceptive, opened::add, text -> { }).get(0).action().run();

        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).hit().text()).isEqualTo("https://example.com/login");
        assertThat(opened.get(0).hit().kind()).isEqualTo(OSC8);
        assertThat(opened.get(0).hit().target()).isEqualTo(URI.create("https://evil.example/login"));
    }

    @Test
    public void anOsc8LinkCopiesItsRealTargetNotItsText() {
        assertThat(TerminalLinkContextMenu.address(link(OSC8, "https://evil.example/login", "https://example.com/login")))
            .isEqualTo("https://evil.example/login");
        // The form the browser receives, as the tooltip shows it: non-ASCII characters percent-encoded.
        assertThat(TerminalLinkContextMenu.address(link(OSC8, "https://example.com/straße", "Straße")))
            .isEqualTo("https://example.com/stra%C3%9Fe");
        assertThat(TerminalLinkContextMenu.address(link(OSC8, "mailto:ops@example.com?subject=Hi", "write us")))
            .isEqualTo("mailto:ops@example.com?subject=Hi");
    }

    @Test
    public void aPlainTextAddressIsCopiedAsPrinted() {
        assertThat(TerminalLinkContextMenu.address(link(AUTO, "https://example.com/docs", "https://example.com/docs")))
            .isEqualTo("https://example.com/docs");
        // An e-mail address opens as mailto: but is copied without it.
        assertThat(TerminalLinkContextMenu.address(link(AUTO, "mailto:ops@example.com", "ops@example.com")))
            .isEqualTo("ops@example.com");
        // A mailto link printed as plain text keeps its scheme.
        assertThat(TerminalLinkContextMenu.address(link(AUTO, "mailto:ops@example.com", "mailto:ops@example.com")))
            .isEqualTo("mailto:ops@example.com");
    }

    @Test
    public void aLinkToAFileGetsOpenFileThenCopyPath() {
        List<Link> opened = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        Link printed = new Link(AUTO, null, "src/App.java:42", new Point(0, 0), new Point(14, 0),
            TerminalFileLink.printed("src/App.java:42"));

        List<Entry> entries = TerminalLinkContextMenu.entries(printed, opened::add, copied::add);

        assertThat(keys(entries)).containsExactly(OPEN_FILE_KEY, COPY_PATH_KEY).inOrder();
        assertThat(OPEN_FILE_KEY).isEqualTo("terminal.contextMenu.openFile");
        assertThat(COPY_PATH_KEY).isEqualTo("terminal.contextMenu.copyPath");
        entries.get(0).action().run();
        assertThat(opened).containsExactly(printed);
        // The path without its line number.
        entries.get(1).action().run();
        assertThat(copied).containsExactly("src/App.java");
    }

    @Test
    public void anOsc8FileLinkCopiesTheRealPathNotItsText() {
        List<String> copied = new ArrayList<>();
        Link osc8 = new Link(OSC8, null, "notes.txt", new Point(0, 0), new Point(8, 0),
            TerminalFileLink.fromFileUri("file://web01/home/daniel/My%20Notes/notes.txt").orElseThrow());

        TerminalLinkContextMenu.entries(osc8, link -> { }, copied::add).get(1).action().run();

        assertThat(copied).containsExactly("/home/daniel/My Notes/notes.txt");
    }

    @Test
    public void aFileThePaneDoesNotOpenGetsNoEntries() {
        Link refused = new Link(OSC8, null, "notes.txt", new Point(0, 0), new Point(8, 0),
            TerminalFileLink.fromFileUri("file://db02/home/daniel/notes.txt").orElseThrow()).withoutFile();

        assertThat(TerminalLinkContextMenu.entries(refused, link -> { }, text -> { })).isEmpty();
    }

    @Test
    public void aLinkWithoutATargetHasNoAddress() {
        assertThrows(IllegalArgumentException.class, () -> TerminalLinkContextMenu.address(link(AUTO, null, "x")));
    }

    @Test
    public void onlyARightButtonPressOnACellLooksUpTheLink() {
        TerminalTextBuffer buffer = new EmulatorTextBufferFixture(40, 4, 10, new KorttyOsc8LinkInfoProvider()).buffer;
        Link found = link(AUTO, "https://example.com/", "https://example.com/");
        List<Point> asked = new ArrayList<>();
        TerminalLinkContextMenu menu = new TerminalLinkContextMenu((b, cell) -> {
            asked.add(cell);
            return found;
        });
        assertThat(menu.link()).isNull();

        menu.pressed(true, buffer, new Point(5, 1));
        assertThat(asked).containsExactly(new Point(5, 1));
        assertThat(menu.link()).isSameInstanceAs(found);

        // Any other press forgets it without a lookup, so a later menu never acts on an old link.
        menu.pressed(false, buffer, new Point(5, 1));
        assertThat(menu.link()).isNull();
        assertThat(asked).hasSize(1);

        menu.pressed(true, buffer, new Point(5, 1));
        assertThat(menu.link()).isSameInstanceAs(found);
        // A right-button press beside the text forgets it too.
        menu.pressed(true, buffer, null);
        assertThat(menu.link()).isNull();
        assertThat(asked).hasSize(2);
    }

    @Test
    public void aRightButtonPressOnNoLinkForgetsTheLastOne() {
        TerminalTextBuffer buffer = new EmulatorTextBufferFixture(40, 4, 10, new KorttyOsc8LinkInfoProvider()).buffer;
        List<Link> answers = new ArrayList<>(List.of(link(OSC8, "https://example.com/", "docs")));
        TerminalLinkContextMenu menu = new TerminalLinkContextMenu(
            (b, cell) -> answers.isEmpty() ? null : answers.remove(0));

        menu.pressed(true, buffer, new Point(1, 0));
        assertThat(menu.link()).isNotNull();
        menu.pressed(true, buffer, new Point(30, 0));
        assertThat(menu.link()).isNull();
    }

    @Test
    public void theMenuActsOnTheLinkUnderThePressInARealBuffer() {
        EmulatorTextBufferFixture fixture = new EmulatorTextBufferFixture(60, 4, 10, new KorttyOsc8LinkInfoProvider());
        fixture.write("see ");
        fixture.link("https://docs.example.com/x", "docs-link");
        fixture.write(" or https://example.com/plain now\r\nmail ops@example.com\r\nplain text");
        Set<de.kortty.core.TerminalLinkDetector.Kind> kinds = WEB_LINK_KINDS;
        TerminalLinkContextMenu menu = new TerminalLinkContextMenu(
            (buffer, cell) -> TerminalLinkResolver.linkAt(buffer, cell, kinds));
        List<String> copied = new ArrayList<>();

        menu.pressed(true, fixture.buffer, new Point(6, 0));
        List<Entry> onOsc8 = TerminalLinkContextMenu.entries(menu.link(), link -> { }, copied::add);
        assertThat(keys(onOsc8)).containsExactly(OPEN_LINK_KEY, COPY_LINK_KEY).inOrder();
        onOsc8.get(1).action().run();

        menu.pressed(true, fixture.buffer, new Point(20, 0));
        TerminalLinkContextMenu.entries(menu.link(), link -> { }, copied::add).get(1).action().run();

        menu.pressed(true, fixture.buffer, new Point(8, 1));
        TerminalLinkContextMenu.entries(menu.link(), link -> { }, copied::add).get(1).action().run();

        assertThat(copied).containsExactly("https://docs.example.com/x", "https://example.com/plain", "ops@example.com")
            .inOrder();

        menu.pressed(true, fixture.buffer, new Point(2, 2));
        assertThat(TerminalLinkContextMenu.entries(menu.link(), link -> { }, copied::add)).isEmpty();
    }

    @Test
    public void withDetectionOffOnlyOsc8LinksGetEntries() {
        EmulatorTextBufferFixture fixture = new EmulatorTextBufferFixture(60, 4, 10, new KorttyOsc8LinkInfoProvider());
        fixture.link("https://docs.example.com/x", "docs-link");
        fixture.write(" https://example.com/plain");
        TerminalLinkContextMenu menu = new TerminalLinkContextMenu(
            (buffer, cell) -> TerminalLinkResolver.linkAt(buffer, cell, Set.of()));

        menu.pressed(true, fixture.buffer, new Point(15, 0));
        assertThat(TerminalLinkContextMenu.entries(menu.link(), link -> { }, text -> { })).isEmpty();

        menu.pressed(true, fixture.buffer, new Point(2, 0));
        assertThat(keys(TerminalLinkContextMenu.entries(menu.link(), link -> { }, text -> { })))
            .containsExactly(OPEN_LINK_KEY, COPY_LINK_KEY).inOrder();
    }

    @Test
    public void thePathUnderThePressIsAFileLinkInARealBuffer() {
        EmulatorTextBufferFixture fixture = new EmulatorTextBufferFixture(60, 4, 10, new KorttyOsc8LinkInfoProvider());
        fixture.write("grep: /etc/nginx/nginx.conf:12: bad directive");
        TerminalLinkContextMenu menu = new TerminalLinkContextMenu(
            (buffer, cell) -> TerminalLinkResolver.linkAt(buffer, cell, TerminalLinkResolver.WEB_AND_PATH_LINK_KINDS));
        List<String> copied = new ArrayList<>();

        menu.pressed(true, fixture.buffer, new Point(10, 0));
        List<Entry> entries = TerminalLinkContextMenu.entries(menu.link(), link -> { }, copied::add);
        assertThat(keys(entries)).containsExactly(OPEN_FILE_KEY, COPY_PATH_KEY).inOrder();
        entries.get(1).action().run();
        assertThat(copied).containsExactly("/etc/nginx/nginx.conf");
    }

    @Test
    public void bothLabelsAreTranslatedInEveryBundle() throws IOException {
        for (String bundle : BUNDLES) {
            Properties properties = new Properties();
            try (InputStream in = TerminalLinkContextMenuTest.class.getResourceAsStream("/i18n/" + bundle)) {
                assertThat(in).isNotNull();
                properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : List.of(OPEN_LINK_KEY, COPY_LINK_KEY, OPEN_FILE_KEY, COPY_PATH_KEY)) {
                String label = properties.getProperty(key);
                assertWithMessage(bundle + " " + key).that(label).isNotEmpty();
                // LanguageManager replaces placeholders with String.replace, so quotes stay single.
                assertWithMessage(bundle + " " + key).that(label).doesNotContain("''");
                if (!bundle.equals("messages.properties")) {
                    assertWithMessage(bundle + " " + key + " is not translated")
                        .that(label).isNotEqualTo(english(key));
                }
            }
        }
    }

    @Test
    public void theSplitPaneMenuPutsTheLinkEntriesFirstAndCopiesThroughKorttyClipboard() throws IOException {
        String splitPane = source("src/main/java/com/sithtermfx/ui/split/TerminalSplitPane.java");

        assertThat(splitPane).contains("TerminalLinkContextMenu.entries( korttyWidget.contextMenuLink(), "
            + "korttyWidget::openLink, KorttyClipboard::setText)");
        int menuStart = splitPane.indexOf("private @NotNull ContextMenu createFullContextMenu(");
        int linkItems = splitPane.indexOf("toMenuItems(linkActions(widget))", menuStart);
        int editItems = splitPane.indexOf("toMenuItems(editActions(actions))", menuStart);
        assertThat(menuStart).isAtLeast(0);
        assertThat(linkItems).isGreaterThan(menuStart);
        assertThat(editItems).isGreaterThan(linkItems);

        String widget = source("src/main/java/de/kortty/ui/KorttyTermWidget.java");
        // The press filter shares the hover's lookup, with the pane's live link kinds.
        assertThat(widget).contains("linkMenu = TerminalLinkContextMenu.install(this, linkFinder);");
        assertThat(widget).contains(
            "(buffer, cell) -> openable(TerminalLinkResolver.linkAt(buffer, cell, plainTextLinkKinds.get()));");
    }

    private static String english(String key) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = TerminalLinkContextMenuTest.class.getResourceAsStream("/i18n/messages.properties")) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties.getProperty(key);
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
