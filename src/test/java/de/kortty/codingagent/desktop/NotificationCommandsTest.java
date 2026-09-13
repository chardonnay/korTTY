package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.testng.annotations.Test;

class NotificationCommandsTest {

    private static final Map<String, String> FLATPAK_ENV = Map.of("FLATPAK_ID", "io.github.chardonnay.korTTY");

    private static Predicate<Path> filesPresent(String... paths) {
        Set<String> present = Set.of(paths);
        return path -> present.contains(path.toString());
    }

    @Test
    void appleScriptLiteralEscapesBackslashAndQuote() {
        assertThat(NotificationCommands.appleScriptLiteral("say \"hi\" C:\\tmp"))
            .isEqualTo("\"say \\\"hi\\\" C:\\\\tmp\"");
        assertThat(NotificationCommands.appleScriptLiteral("")).isEqualTo("\"\"");
        assertThat(NotificationCommands.appleScriptLiteral(null)).isEqualTo("\"\"");
    }

    @Test
    void appleScriptLiteralStripsControlCharacters() {
        assertThat(NotificationCommands.appleScriptLiteral("a\u0007b\u001b[0mc\u007fd"))
            .isEqualTo("\"ab[0mcd\"");
        assertThat(NotificationCommands.appleScriptLiteral("line1\nline2\r\n\ttab"))
            .isEqualTo("\"line1 line2   tab\"");
        assertThat(NotificationCommands.appleScriptLiteral("ünïcödé ✋")).isEqualTo("\"ünïcödé ✋\"");
    }

    @Test
    void osascriptArgvShape() {
        List<String> argv = NotificationCommands.osascript("korTTY", "claude needs a decision", "~/proj › /home/me/proj");

        assertThat(argv).containsExactly(
            "/usr/bin/osascript", "-e",
            "display notification \"~/proj › /home/me/proj\" with title \"korTTY\" subtitle \"claude needs a decision\"")
            .inOrder();
    }

    @Test
    void osascriptEscapesEveryField() {
        List<String> argv = NotificationCommands.osascript("kor\"TTY", "t\\", "b\"");

        assertThat(argv.get(2))
            .isEqualTo("display notification \"b\\\"\" with title \"kor\\\"TTY\" subtitle \"t\\\\\"");
    }

    @Test
    void notifySendArgvOutsideFlatpak() {
        List<String> argv = NotificationCommands.notifySend(
            "korTTY", "utilities-terminal", "claude finished", "~/proj › /home/me/proj", Map.of());

        assertThat(argv).containsExactly(
            "notify-send", "--app-name=korTTY", "--icon=utilities-terminal", "--urgency=normal",
            "--expire-time=8000", "claude finished", "~/proj › /home/me/proj").inOrder();
    }

    @Test
    void notifySendArgvIsWrappedInsideFlatpak() {
        List<String> argv = NotificationCommands.notifySend(
            "korTTY", "io.github.chardonnay.korTTY", "title", "body", FLATPAK_ENV);

        assertThat(argv).containsExactly(
            "flatpak-spawn", "--host", "--watch-bus",
            "notify-send", "--app-name=korTTY", "--icon=io.github.chardonnay.korTTY", "--urgency=normal",
            "--expire-time=8000", "title", "body").inOrder();
    }

    @Test
    void notifySendToleratesNullTitleAndBody() {
        List<String> argv = NotificationCommands.notifySend("korTTY", "x", null, null, null);

        assertThat(argv).hasSize(7);
        assertThat(argv.get(5)).isEmpty();
        assertThat(argv.get(6)).isEmpty();
    }

    @Test
    void linuxIconPrefersTheFlatpakAppId() {
        PlatformProbe flatpak = new PlatformProbe("Linux", "/app/bin/korTTY", true, false);

        assertThat(NotificationCommands.linuxIcon(flatpak, path -> true)).isEqualTo("io.github.chardonnay.korTTY");
    }

    @Test
    void linuxIconUsesTheInstalledPngNextToTheLauncher() {
        PlatformProbe deb = new PlatformProbe("Linux", "/opt/kortty/bin/korTTY", false, false);

        assertThat(NotificationCommands.linuxIcon(deb, filesPresent("/opt/kortty/lib/korTTY.png")))
            .isEqualTo("/opt/kortty/lib/korTTY.png");
        assertThat(NotificationCommands.linuxIcon(deb, path -> false)).isEqualTo("utilities-terminal");
    }

    @Test
    void linuxIconFallsBackToTheThemeIconWhenUnpackaged() {
        PlatformProbe unpackaged = new PlatformProbe("Linux", null, false, false);

        assertThat(NotificationCommands.linuxIcon(unpackaged, path -> true)).isEqualTo("utilities-terminal");
    }

    @Test
    void truncateCutsAtTheLimitWithAnEllipsis() {
        String exact = "x".repeat(200);
        String longer = "y".repeat(201);

        assertThat(NotificationCommands.truncate(exact, 200)).isEqualTo(exact);
        String cut = NotificationCommands.truncate(longer, 200);
        assertThat(cut).hasLength(200);
        assertThat(cut).endsWith("…");
        assertThat(cut).startsWith("y".repeat(199));
        assertThat(NotificationCommands.truncate(null, 200)).isEmpty();
        assertThat(NotificationCommands.truncate("abc", 0)).isEmpty();
        assertThat(NotificationCommands.truncate("abc", 1)).isEqualTo("…");
    }

    @Test
    void truncateDoesNotSplitASurrogatePair() {
        String text = "ab" + "\uD83D\uDE00" + "cdef";

        assertThat(NotificationCommands.truncate(text, 4)).isEqualTo("ab…");
        assertThat(NotificationCommands.truncate(text, 5)).isEqualTo("ab\uD83D\uDE00…");
    }

    @Test
    void isOnPathWalksTheGivenPathEntries() {
        Map<String, String> env = Map.of("PATH", "/opt/bin::/usr/local/bin:/usr/bin");
        Predicate<Path> executable = filesPresent("/usr/bin/notify-send");

        assertThat(DesktopNotifierBackends.isOnPath("notify-send", env, executable)).isTrue();
        assertThat(DesktopNotifierBackends.isOnPath("gdbus", env, executable)).isFalse();
        assertThat(DesktopNotifierBackends.isOnPath("notify-send", Map.of("PATH", "/opt/bin"), executable)).isFalse();
    }

    @Test
    void isOnPathFallsBackToTheDefaultPath() {
        Predicate<Path> executable = filesPresent("/usr/bin/notify-send");

        assertThat(DesktopNotifierBackends.isOnPath("notify-send", Map.of(), executable)).isTrue();
        assertThat(DesktopNotifierBackends.isOnPath("notify-send", null, executable)).isTrue();
        assertThat(DesktopNotifierBackends.isOnPath("notify-send", Map.of("PATH", "/nowhere"), executable)).isFalse();
    }
}
