package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.testng.annotations.Test;

class PaneLocationTest {

    private static final List<String> REQUESTED_KEYS = new ArrayList<>();

    /** Formats the English bundle values so the assertions read like the real panel. */
    private static final BiFunction<String, Object[], String> ENGLISH = (key, args) -> {
        REQUESTED_KEYS.add(key);
        return switch (key) {
            case "codingAgent.panel.location" -> args[0] + " › " + args[1] + " › Pane " + args[2];
            case "codingAgent.panel.locationNoPane" -> args[0] + " › " + args[1];
            case "codingAgent.panel.window" -> "Window " + args[0];
            default -> throw new AssertionError("unexpected key " + key);
        };
    };

    private static final BiFunction<String, Object[], String> IDENTITY = (key, args) -> {
        throw new AssertionError("no message expected for " + key);
    };

    @Test
    void shortTextOmitsThePaneUnlessSplit() {
        assertThat(new PaneLocation(0, 1, "api", 0, 1, null).shortText()).isEqualTo("api");
        assertThat(new PaneLocation(0, 1, "api", 1, 2, null).shortText()).isEqualTo("api › Pane 2");
        assertThat(new PaneLocation(0, 1, "api", 0, 3, "/x").shortText()).isEqualTo("api › Pane 1");
        assertThat(new PaneLocation(0, 1, null, 0, 1, null).shortText()).isEmpty();
    }

    @Test
    void singleWindowTextIsTheShortTextWithoutMessages() {
        assertThat(new PaneLocation(0, 1, "api", 0, 1, null).text(IDENTITY)).isEqualTo("api");
        assertThat(new PaneLocation(0, 1, "api", 1, 2, null).text(IDENTITY)).isEqualTo("api › Pane 2");
    }

    @Test
    void multiWindowTextUsesTheLocationKeys() {
        REQUESTED_KEYS.clear();
        assertThat(new PaneLocation(1, 2, "api", 1, 2, null).text(ENGLISH)).isEqualTo("Window 2 › api › Pane 2");
        assertThat(REQUESTED_KEYS).containsExactly("codingAgent.panel.window", "codingAgent.panel.location").inOrder();

        REQUESTED_KEYS.clear();
        assertThat(new PaneLocation(0, 3, "api", 0, 1, null).text(ENGLISH)).isEqualTo("Window 1 › api");
        assertThat(REQUESTED_KEYS).containsExactly("codingAgent.panel.window", "codingAgent.panel.locationNoPane")
            .inOrder();
    }

    @Test
    void knownWorkingDirectoryIsAppendedWithHomeAbbreviated() {
        String home = System.getProperty("user.home");
        assertThat(new PaneLocation(0, 1, "api", 0, 1, home + "/proj/api").text(IDENTITY)).isEqualTo("api · ~/proj/api");
        assertThat(new PaneLocation(0, 1, "api", 0, 1, home).text(IDENTITY)).isEqualTo("api · ~");
        assertThat(new PaneLocation(1, 2, "api", 1, 2, "/srv/app").text(ENGLISH))
            .isEqualTo("Window 2 › api › Pane 2 · /srv/app");
        assertThat(new PaneLocation(0, 1, "api", 0, 1, "  ").text(IDENTITY)).isEqualTo("api");
        assertThat(new PaneLocation(0, 1, "api", 0, 1, null).text(IDENTITY)).isEqualTo("api");
    }

    @Test
    void abbreviateHome() {
        assertThat(PaneLocation.abbreviateHome("/home/me/proj", "/home/me")).isEqualTo("~/proj");
        assertThat(PaneLocation.abbreviateHome("/home/me/proj", "/home/me/")).isEqualTo("~/proj");
        assertThat(PaneLocation.abbreviateHome("/home/me", "/home/me")).isEqualTo("~");
        assertThat(PaneLocation.abbreviateHome("/home/meow/x", "/home/me")).isEqualTo("/home/meow/x");
        assertThat(PaneLocation.abbreviateHome("/srv/app", "/home/me")).isEqualTo("/srv/app");
        assertThat(PaneLocation.abbreviateHome("C:\\Users\\me\\proj", "C:\\Users\\me")).isEqualTo("~\\proj");
        assertThat(PaneLocation.abbreviateHome(null, "/home/me")).isNull();
        assertThat(PaneLocation.abbreviateHome("/x", null)).isEqualTo("/x");
        assertThat(PaneLocation.abbreviateHome("/x", "")).isEqualTo("/x");
        assertThat(PaneLocation.abbreviateHome("/x/y", "/")).isEqualTo("/x/y");
    }
}
