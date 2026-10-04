package de.kortty.ui;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.model.hyperlinks.TextProcessing;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * SithTermFX's default OSC 8 provider opened {@code file:} links with {@code java.awt.Desktop.open},
 * so a link printed by a server could launch a local program with one click. These cases pin
 * korTTY's replacement: only web and mail links become links, SithTermFX's navigation of them only
 * hands them back to the pane (which opens them only on a Cmd/Ctrl+click, see
 * {@link TerminalLinkClickPolicyTest}), and nothing in the link path references {@code java.awt}.
 */
public class KorttyOsc8LinkInfoProviderTest {

    @Test
    public void webLinkCarriesItsTarget() {
        KorttyLinkInfo link = new KorttyOsc8LinkInfoProvider().createLinkInfo("https://example.com/docs");

        assertThat(link).isNotNull();
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/docs"));
    }

    @Test
    public void sithTermFxNavigationHandsTheLinkToThePaneAndOpensNothingItself() throws IOException {
        // SithTermFX navigates a link on the open gesture. The link holds no opener: it hands itself
        // to the pane's follower, which opens it after the host-mismatch question.
        List<KorttyLinkInfo> followed = new java.util.ArrayList<>();
        KorttyLinkInfo link = new KorttyOsc8LinkInfoProvider(() -> false, followed::add)
            .createLinkInfo("https://example.com/docs");

        assertThat(link).isNotNull();
        link.navigate();
        assertThat(followed).containsExactly(link);
        // Without a pane, navigating does nothing.
        KorttyLinkInfo paneless = new KorttyOsc8LinkInfoProvider().createLinkInfo("https://example.com/docs");
        assertThat(paneless).isNotNull();
        paneless.navigate();
        assertThat(classFileText(KorttyLinkInfo.class)).doesNotContain("de/kortty/ui/TerminalLinkOpener");
        // The provider still calls the opener's static allowlist, but holds or passes on no opener:
        // a field or parameter of that type would show as its descriptor "L...TerminalLinkOpener;".
        assertThat(classFileText(KorttyOsc8LinkInfoProvider.class)).doesNotContain("de/kortty/ui/TerminalLinkOpener;");
        assertThat(classFileText(KorttyOsc8LinkInfoProvider.class)).doesNotContain("HostServices");
    }

    @Test
    public void aFileLinkIsHandedToThePaneToo() {
        List<KorttyLinkInfo> followed = new java.util.ArrayList<>();
        KorttyLinkInfo file = new KorttyOsc8LinkInfoProvider(() -> true, followed::add)
            .createLinkInfo("file:///home/daniel/notes.txt");

        assertThat(file).isNotNull();
        file.navigate();
        assertThat(followed).containsExactly(file);
    }

    @Test
    public void mailLinkIsALink() {
        assertThat(new KorttyOsc8LinkInfoProvider().createLinkInfo("mailto:someone@example.com")).isNotNull();
    }

    @DataProvider
    public Object[][] plainTextTargets() {
        return new Object[][] {
            {"file:///x"},
            {"file://localhost/x"},
            {"file:////host/share/x.exe"},
            {"javascript:alert(1)"},
            {"news:comp.lang.java"},
            {"data:text/plain,hi"},
            {"mailto:someone@example.com?attach=/etc/passwd"},
            {"https://exa‮mple.com/"},
        };
    }

    @Test(dataProvider = "plainTextTargets")
    public void otherTargetsStayPlainText(String target) {
        assertWithMessage(target).that(new KorttyOsc8LinkInfoProvider().createLinkInfo(target)).isNull();
    }

    @Test
    public void aFileTargetIsALinkOnlyInAPaneThatOpensFiles() {
        java.util.concurrent.atomic.AtomicBoolean opensFiles = new java.util.concurrent.atomic.AtomicBoolean();
        KorttyOsc8LinkInfoProvider provider = new KorttyOsc8LinkInfoProvider(opensFiles::get);

        assertThat(provider.createLinkInfo("file:///home/daniel/notes.txt")).isNull();

        opensFiles.set(true);
        KorttyLinkInfo file = provider.createLinkInfo("file:///home/daniel/notes.txt");
        assertThat(file).isNotNull();
        // A file link has no browser target: it opens as text in the Snippet Editor, never launched.
        assertThat(file.target()).isNull();
        assertThat(file.file()).isNotNull();
        assertThat(file.file().path()).isEqualTo("/home/daniel/notes.txt");
        assertThat(file.file().uri()).isEqualTo(URI.create("file:///home/daniel/notes.txt"));
        // A web link is a web link either way.
        assertThat(provider.createLinkInfo("https://example.com/").target()).isEqualTo(URI.create("https://example.com/"));
    }

    @Test
    public void unsafeFileTargetsStayPlainTextEvenWhereFilesOpen() {
        KorttyOsc8LinkInfoProvider provider = new KorttyOsc8LinkInfoProvider(() -> true);

        for (String target : List.of("file:////host/share/x.exe", "file://user@host/x", "file:///tmp/%E2%80%AEx",
                "javascript:alert(1)", "news:comp.lang.java")) {
            assertWithMessage(target).that(provider.createLinkInfo(target)).isNull();
        }
    }

    @Test
    public void theFileQuestionIsAskedOnlyForFileTargets() {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        KorttyOsc8LinkInfoProvider provider = new KorttyOsc8LinkInfoProvider(() -> {
            asked.incrementAndGet();
            return true;
        });

        provider.createLinkInfo("https://example.com/");
        provider.createLinkInfo("javascript:alert(1)");
        assertThat(asked.get()).isEqualTo(0);
        provider.createLinkInfo("FILE:///etc/hosts");
        assertThat(asked.get()).isEqualTo(1);
    }

    @Test
    public void emulatorDrawsARefusedLinkAsPlainTextAndKeepsAnAllowedOne() {
        StyleState styleState = new StyleState();
        TextProcessing processing = new TextProcessing(new TextStyle(), HyperlinkStyle.HighlightMode.HOVER_WITH_BOTH_COLORS);
        processing.setLinkInfoProvider(new KorttyOsc8LinkInfoProvider());
        TerminalTextBuffer buffer = new TerminalTextBuffer(40, 3, styleState, 100, processing);
        processing.setTerminalTextBuffer(buffer);
        SithTerminal terminal = new SithTerminal(noDisplay(), buffer, styleState);

        // ESC ] 8 ; ; file:////host/share/x.exe ST setup.exe ESC ] 8 ; ; ST, then a web link.
        terminal.setLinkUriStarted("file:////host/share/x.exe");
        terminal.writeString("setup.exe");
        terminal.setLinkUriFinished();
        terminal.writeString(" ");
        terminal.setLinkUriStarted("https://example.com/");
        terminal.writeString("docs");
        terminal.setLinkUriFinished();

        assertThat(buffer.getStyleAt(0, 0)).isNotInstanceOf(HyperlinkStyle.class);
        TextStyle docs = buffer.getStyleAt(10, 0);
        assertThat(docs).isInstanceOf(HyperlinkStyle.class);
        assertThat(((HyperlinkStyle) docs).getLinkInfo()).isInstanceOf(KorttyLinkInfo.class);
        assertThat(((KorttyLinkInfo) ((HyperlinkStyle) docs).getLinkInfo()).target())
            .isEqualTo(URI.create("https://example.com/"));
    }

    @Test
    public void linkClassesNeverReferenceJavaAwt() throws IOException {
        for (Class<?> type : List.of(KorttyOsc8LinkInfoProvider.class, KorttyLinkInfo.class, TerminalLinkOpener.class,
                TerminalLinkClickPolicy.class, TerminalFileLink.class)) {
            assertWithMessage(type.getSimpleName()).that(classFileText(type)).doesNotContain("java/awt");
        }
    }

    @Test
    public void everyPaneInstallsTheKorttyProvider() throws IOException {
        String widget = Files.readString(Path.of("src/main/java/de/kortty/ui/KorttyTermWidget.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        // In the constructor, so the provider is in place before any pane is started.
        int constructor = widget.indexOf("super(columns, lines, settingsProvider);");
        int install = widget.indexOf("\n        setLinkInfoProvider(new KorttyOsc8LinkInfoProvider(panel::fileLinksEnabled, panel::followLink));\n");
        int panelFactory = widget.indexOf("protected TerminalPanel createTerminalPanel(");
        assertThat(constructor).isAtLeast(0);
        assertThat(install).isGreaterThan(constructor);
        assertThat(install).isLessThan(panelFactory);
    }

    @Test
    public void everyTerminalWidgetIsAKorttyTermWidget() throws IOException {
        // A plain SithTermFxWidget, or another subclass of it, would keep SithTermFX's Desktop.open provider.
        List<Path> offenders;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            offenders = files.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.endsWith(Path.of("de/kortty/ui/KorttyTermWidget.java")))
                .filter(path -> {
                    try {
                        String source = Files.readString(path, StandardCharsets.UTF_8);
                        return source.contains("new SithTermFxWidget(") || source.contains("extends SithTermFxWidget");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .toList();
        }
        assertThat(offenders).isEmpty();
    }

    /** The compiled class, so comments that mention {@code java.awt.Desktop} do not count. */
    private static String classFileText(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertWithMessage(type.getName()).that(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    /** A display that ignores everything; SithTermFX's own test doubles are not published. */
    private static TerminalDisplay noDisplay() {
        return (TerminalDisplay) Proxy.newProxyInstance(TerminalDisplay.class.getClassLoader(),
            new Class<?>[] {TerminalDisplay.class}, (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (type.isPrimitive() && type != void.class) {
                    return Array.get(Array.newInstance(type, 1), 0);
                }
                return type == String.class ? "" : null;
            });
    }
}
