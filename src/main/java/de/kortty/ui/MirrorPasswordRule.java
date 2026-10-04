package de.kortty.ui;

import de.kortty.core.PasswordPromptDetector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Keeps a password typed in broadcast mode out of the panes that do not ask for one.
 *
 * <p>While the cursor of the pane the user types in sits on a password prompt (such as
 * {@code [sudo] password for anna:}, see {@link PasswordPromptDetector#isPasswordPromptLine}), every
 * key typed there, Enter included, is mirrored only into the panes whose cursor sits on a password
 * prompt too. In a pane at an ordinary shell prompt the password would otherwise run as a command,
 * land in the shell history and in the session journal, and be shown on screen. At any other line
 * the keys reach every pane as before.
 *
 * <p>The cursor line is a heuristic, as everywhere korTTY looks for password prompts: there is no
 * client-side signal that a remote program stopped echoing. A pane whose line cannot be read counts
 * as not at a prompt. Pure, FX-free.
 */
final class MirrorPasswordRule {

    private MirrorPasswordRule() {
    }

    /** Whether a key typed in a pane whose cursor line is {@code sourceLine} may reach a pane at {@code targetLine}. */
    static boolean admits(@Nullable String sourceLine, @Nullable String targetLine) {
        return !PasswordPromptDetector.isPasswordPromptLine(sourceLine)
            || PasswordPromptDetector.isPasswordPromptLine(targetLine);
    }

    /**
     * The panes a key typed in a pane whose cursor line is {@code sourceLine} may reach. The cursor
     * line of a pane is read through {@code cursorLineOf} only while the source is at a password
     * prompt, so an ordinary key reads no other pane.
     */
    static <W> @NotNull Predicate<W> receiversOf(@Nullable String sourceLine,
                                                 @NotNull Function<? super W, String> cursorLineOf) {
        Objects.requireNonNull(cursorLineOf, "cursorLineOf");
        if (!PasswordPromptDetector.isPasswordPromptLine(sourceLine)) {
            return pane -> true;
        }
        return pane -> PasswordPromptDetector.isPasswordPromptLine(cursorLineOf.apply(pane));
    }
}
