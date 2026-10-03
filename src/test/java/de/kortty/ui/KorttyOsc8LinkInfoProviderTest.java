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
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * SithTermFX's default OSC 8 provider opened {@code file:} links with {@code java.awt.Desktop.open},
 * so a link printed by a server could launch a local program with one click. These cases pin
 * korTTY's replacement: only web and mail links become links, they open through the injected
 * opener, and nothing in the link path references {@code java.awt}.
 */
public class KorttyOsc8LinkInfoProviderTest {

    @Test
    public void webLinkCarriesItsTargetAndOpensThroughTheOpener() {
        List<String> opened = new ArrayList<>();
        KorttyOsc8LinkInfoProvider provider = new KorttyOsc8LinkInfoProvider(new TerminalLinkOpener(opened::add));

        KorttyLinkInfo link = provider.createLinkInfo("https://example.com/docs");

        assertThat(link).isNotNull();
        assertThat(link.target()).isEqualTo(URI.create("https://example.com/docs"));
        assertThat(opened).isEmpty();
        link.navigate();
        assertThat(opened).containsExactly("https://example.com/docs");
    }

    @Test
    public void mailLinkIsALink() {
        KorttyOsc8LinkInfoProvider provider = new KorttyOsc8LinkInfoProvider(new TerminalLinkOpener(target -> { }));

        assertThat(provider.createLinkInfo("mailto:someone@example.com")).isNotNull();
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
        List<String> opened = new ArrayList<>();
        KorttyOsc8LinkInfoProvider provider = new KorttyOsc8LinkInfoProvider(new TerminalLinkOpener(opened::add));

        assertWithMessage(target).that(provider.createLinkInfo(target)).isNull();
        assertThat(opened).isEmpty();
    }

    @Test
    public void emulatorDrawsARefusedLinkAsPlainTextAndKeepsAnAllowedOne() {
        List<String> opened = new ArrayList<>();
        StyleState styleState = new StyleState();
        TextProcessing processing = new TextProcessing(new TextStyle(), HyperlinkStyle.HighlightMode.HOVER_WITH_BOTH_COLORS);
        processing.setLinkInfoProvider(new KorttyOsc8LinkInfoProvider(new TerminalLinkOpener(opened::add)));
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
        ((HyperlinkStyle) docs).getLinkInfo().navigate();
        assertThat(opened).containsExactly("https://example.com/");
    }

    @Test
    public void linkClassesNeverReferenceJavaAwt() throws IOException {
        for (Class<?> type : List.of(KorttyOsc8LinkInfoProvider.class, KorttyLinkInfo.class, TerminalLinkOpener.class)) {
            assertWithMessage(type.getSimpleName()).that(classFileText(type)).doesNotContain("java/awt");
        }
    }

    @Test
    public void everyPaneInstallsTheKorttyProvider() throws IOException {
        String widget = Files.readString(Path.of("src/main/java/de/kortty/ui/KorttyTermWidget.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        // In the constructor, so the provider is in place before any pane is started.
        int constructor = widget.indexOf("super(columns, lines, settingsProvider);");
        int install = widget.indexOf("\n        setLinkInfoProvider(new KorttyOsc8LinkInfoProvider());\n");
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
