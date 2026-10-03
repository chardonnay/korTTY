package de.kortty.ui;

import de.kortty.ui.KeyTypedResidueGuard.Residue;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCombination.ModifierValue;
import javafx.scene.input.KeyEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * The main window's one scene-level keyboard shortcut router.
 *
 * <p>A scene event filter runs before the focused node, so a chord routed here works while a
 * terminal has the focus (the terminal would otherwise encode it), and also with the menu bar
 * hidden or in terminal-only fullscreen, where menu accelerators cannot fire. Entries are tried in
 * registration order and the first whose chord and scope both hold wins: it runs its action,
 * consumes the KEY_PRESSED and arms a {@link KeyTypedResidueGuard} with the chord's residue. The
 * router's single KEY_TYPED filter then swallows that residue before the terminal, or broadcast
 * mode's KEY_TYPED mirror in TerminalSplitPane, can type it. The guard is cleared at the top of
 * every KEY_PRESSED, before any entry is tried.
 *
 * <p>Every new window-wide chord registers here. Its KeyCodeCombination constant stays in
 * MainWindow and is also set on its menu item for display, so MainWindowAcceleratorUniquenessTest
 * covers it. Chord predicates are pure functions of a {@link KeyPress}, which carries an explicit
 * macOS flag, so they are unit-tested without the JavaFX toolkit.
 */
final class SceneShortcutRouter {

    /** Scope of a chord that applies whatever tab is selected. */
    static final BooleanSupplier ALWAYS = () -> true;

    private final boolean macOs;
    private final List<Entry> entries = new ArrayList<>();
    private final KeyTypedResidueGuard residueGuard = new KeyTypedResidueGuard();

    SceneShortcutRouter(boolean macOs) {
        this.macOs = macOs;
    }

    /**
     * Registers a chord that, while {@code scope} holds, runs {@code action}, consumes the
     * KEY_PRESSED and swallows {@code residue} from the KEY_TYPED that follows it.
     */
    SceneShortcutRouter consume(@NotNull Predicate<KeyPress> chord, @NotNull BooleanSupplier scope,
                                @NotNull Runnable action, @NotNull Residue residue) {
        entries.add(new Entry(chord, scope, action, Objects.requireNonNull(residue, "residue")));
        return this;
    }

    /**
     * Registers a chord that, while {@code scope} holds, runs {@code action} and lets the KEY_PRESSED
     * carry on to the later entries and the focused node.
     */
    SceneShortcutRouter observe(@NotNull Predicate<KeyPress> chord, @NotNull BooleanSupplier scope,
                                @NotNull Runnable action) {
        entries.add(new Entry(chord, scope, action, null));
        return this;
    }

    void install(@NotNull Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        scene.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
    }

    void onKeyPressed(@NotNull KeyEvent event) {
        // First, before any entry can return: a chord whose platform delivers no KEY_TYPED must not
        // leave the guard armed for the next real character.
        residueGuard.reset();
        KeyPress press = KeyPress.of(event, macOs);
        for (Entry entry : entries) {
            if (!entry.chord().test(press) || !entry.scope().getAsBoolean()) {
                continue;
            }
            entry.action().run();
            if (entry.residue() == null) {
                continue;
            }
            event.consume();
            residueGuard.arm(entry.residue());
            return;
        }
    }

    void onKeyTyped(@NotNull KeyEvent event) {
        if (residueGuard.swallow(event.getCharacter())) {
            event.consume();
        }
    }

    /** A registered chord; a {@code null} residue marks an observer that does not consume. */
    private record Entry(Predicate<KeyPress> chord, BooleanSupplier scope, Runnable action,
                         @Nullable Residue residue) {
    }

    /** The facts of one KEY_PRESSED, read once so that every chord predicate is a pure function of them. */
    record KeyPress(@NotNull KeyCode code, @NotNull String text, @NotNull String character,
                    boolean shift, boolean ctrl, boolean alt, boolean meta, boolean macOs) {

        static KeyPress of(@NotNull KeyEvent event, boolean macOs) {
            return new KeyPress(event.getCode(), Objects.requireNonNullElse(event.getText(), ""),
                Objects.requireNonNullElse(event.getCharacter(), ""), event.isShiftDown(),
                event.isControlDown(), event.isAltDown(), event.isMetaDown(), macOs);
        }

        /**
         * {@link KeyCombination#match} without the toolkit: SHORTCUT is Cmd (meta) on macOS and Ctrl
         * elsewhere, a modifier the combination marks DOWN must be down and one it leaves UP must be up.
         */
        boolean matches(@NotNull KeyCombination combination) {
            if (!(combination instanceof KeyCodeCombination keyCodeCombination)) {
                throw new IllegalArgumentException("Only KeyCodeCombination chords can be routed: " + combination);
            }
            if (keyCodeCombination.getCode() != code) {
                return false;
            }
            ModifierValue shortcut = keyCodeCombination.getShortcut();
            ModifierValue control = macOs
                ? keyCodeCombination.getControl() : withShortcut(keyCodeCombination.getControl(), shortcut);
            ModifierValue metaValue = macOs
                ? withShortcut(keyCodeCombination.getMeta(), shortcut) : keyCodeCombination.getMeta();
            return holds(keyCodeCombination.getShift(), shift)
                && holds(control, ctrl)
                && holds(keyCodeCombination.getAlt(), alt)
                && holds(metaValue, meta);
        }

        private static ModifierValue withShortcut(ModifierValue modifier, ModifierValue shortcut) {
            if (modifier == ModifierValue.DOWN || shortcut == ModifierValue.DOWN) {
                return ModifierValue.DOWN;
            }
            if (modifier == ModifierValue.ANY || shortcut == ModifierValue.ANY) {
                return ModifierValue.ANY;
            }
            return ModifierValue.UP;
        }

        private static boolean holds(ModifierValue value, boolean down) {
            return switch (value) {
                case DOWN -> down;
                case UP -> !down;
                case ANY -> true;
            };
        }
    }
}
