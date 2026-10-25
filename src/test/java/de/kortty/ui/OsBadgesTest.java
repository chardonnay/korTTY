package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.ui.OsBadges.Badge;
import de.kortty.ui.OsBadges.Family;
import de.kortty.ui.OsBadges.Layer;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * The built-in OS badges: ids are unique, kebab-case and stable; every badge has art whose paths are SVG path
 * data with the right argument counts and whose fills are hex colours; {@link OsBadges#builtIn(String)} finds
 * each badge and nothing else; and the families list as Windows, Linux, Unix, BSD. The JavaFX node is built on
 * the FX thread and is not exercised here.
 */
class OsBadgesTest {

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern PATH_DATA = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz0-9.,+\\-\\s]+");
    private static final Pattern PATH_TOKEN = Pattern.compile("[A-Za-z]|[-+]?(\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern HEX_FILL = Pattern.compile("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?");

    @Test
    void idsAreUniqueAndKebabCase() {
        List<String> ids = OsBadges.builtIn().stream().map(Badge::id).toList();

        assertThat(ids).containsNoDuplicates();
        for (String id : ids) {
            assertWithMessage("id %s is kebab-case", id).that(KEBAB_CASE.matcher(id).matches()).isTrue();
        }
    }

    @Test
    void theDocumentedBadgesArePresent() {
        List<String> ids = OsBadges.builtIn().stream().map(Badge::id).toList();

        assertThat(ids).containsAtLeast("windows-xp", "windows-10", "windows-11", "windows-server",
                "linux", "redhat", "rocky", "almalinux", "centos", "fedora", "debian", "ubuntu", "arch", "suse",
                "solaris", "aix", "hp-ux", "dec", "freebsd", "openbsd");
    }

    @Test
    void everyBadgeHasNamedArtWithValidPathsAndFills() {
        for (Badge badge : OsBadges.builtIn()) {
            assertWithMessage("name of %s", badge.id()).that(badge.name()).isNotEmpty();
            assertWithMessage("layers of %s", badge.id()).that(badge.layers()).isNotEmpty();
            for (Layer layer : badge.layers()) {
                String path = layer.path();
                assertWithMessage("path data of %s only uses commands and numbers", badge.id())
                        .that(PATH_DATA.matcher(path).matches()).isTrue();
                assertWithMessage("path of %s starts with a moveto", badge.id()).that(path.trim()).matches("[Mm].*");
                assertCommandArguments(badge.id(), path);
                assertWithMessage("fill of a layer of %s is a hex colour", badge.id())
                        .that(HEX_FILL.matcher(layer.fill()).matches()).isTrue();
            }
        }
    }

    @Test
    void builtInFindsEachBadgeAndNothingElse() {
        for (Badge badge : OsBadges.builtIn()) {
            assertThat(OsBadges.builtIn(badge.id())).hasValue(badge);
        }
        assertThat(OsBadges.builtIn("no-such-os")).isEmpty();
        assertThat(OsBadges.builtIn("Linux")).isEmpty();
    }

    @Test
    void familiesListInTheDocumentedOrder() {
        List<Family> families = OsBadges.builtIn().stream().map(Badge::family).toList();

        assertThat(families).isInOrder(Comparator.comparing(Family::ordinal));
        assertThat(families.stream().distinct().toList())
                .containsExactly(Family.WINDOWS, Family.LINUX, Family.UNIX, Family.BSD).inOrder();
    }

    /** Each command takes a fixed number of arguments, and a path may repeat them only in whole groups. */
    private static void assertCommandArguments(String id, String path) {
        Matcher token = PATH_TOKEN.matcher(path);
        char command = 0;
        int numbers = 0;
        while (token.find()) {
            String text = token.group();
            if (Character.isLetter(text.charAt(0))) {
                checkArguments(id, command, numbers);
                command = text.charAt(0);
                numbers = 0;
            } else {
                assertWithMessage("path of %s starts with a command", id).that(command).isNotEqualTo((char) 0);
                numbers++;
            }
        }
        checkArguments(id, command, numbers);
    }

    private static void checkArguments(String id, char command, int numbers) {
        if (command == 0) {
            return;
        }
        int arity = switch (Character.toUpperCase(command)) {
            case 'Z' -> 0;
            case 'H', 'V' -> 1;
            case 'M', 'L', 'T' -> 2;
            case 'S', 'Q' -> 4;
            case 'C' -> 6;
            case 'A' -> 7;
            default -> throw new IllegalArgumentException("not an SVG path command in " + id + ": " + command);
        };
        if (arity == 0) {
            assertWithMessage("%s in %s takes no arguments", command, id).that(numbers).isEqualTo(0);
        } else {
            assertWithMessage("arguments after '%s' in %s", command, id).that(numbers).isGreaterThan(0);
            assertWithMessage("arguments after '%s' in %s", command, id).that(numbers % arity).isEqualTo(0);
        }
    }
}
