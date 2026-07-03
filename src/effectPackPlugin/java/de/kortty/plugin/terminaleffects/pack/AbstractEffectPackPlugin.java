package de.kortty.plugin.terminaleffects.pack;

import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import de.kortty.plugin.terminaleffects.TerminalEffectAppearance;
import de.kortty.plugin.terminaleffects.TerminalEffectContext;
import de.kortty.plugin.terminaleffects.TerminalEffectPlugin;
import de.kortty.plugin.terminaleffects.TerminalEffectPreview;
import de.kortty.plugin.terminaleffects.TerminalEffectPreviewCanvas;
import de.kortty.plugin.terminaleffects.TerminalEffectSession;

import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Base class for the built-in effect pack plugins: holds metadata and the effect appearance,
 * creates a {@link PackEffectSession} with the effect's overlay and provides a themed
 * {@link TerminalEffectPreviewCanvas} preview.
 */
abstract class AbstractEffectPackPlugin implements TerminalEffectPlugin {

    private final String id;
    private final String displayName;
    private final String descriptionKey;
    private final String descriptionEnglish;
    private final TerminalEffectAppearance appearance;

    protected AbstractEffectPackPlugin(
            String id,
            String displayName,
            String descriptionKey,
            String descriptionEnglish,
            TerminalEffectAppearance appearance) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.descriptionKey = Objects.requireNonNull(descriptionKey, "descriptionKey");
        this.descriptionEnglish = Objects.requireNonNull(descriptionEnglish, "descriptionEnglish");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String displayName() {
        return displayName;
    }

    @Override
    public final String description() {
        return PackLocalization.localized(descriptionKey, descriptionEnglish);
    }

    public final TerminalEffectAppearance appearance() {
        return appearance;
    }

    @Override
    public TerminalEffectSession createSession(TerminalEffectContext context) {
        return new PackEffectSession(context, appearance, () -> createOverlay(context::animationSpeed));
    }

    @Override
    public TerminalEffectPreview createPreview() {
        return TerminalEffectPreviewCanvas.builder()
                .backgroundColor(appearance.backgroundColor())
                .foregroundColor(appearance.foregroundColor())
                .lines(previewLines())
                .overlay(
                        () -> createOverlay(() -> TerminalEffectAnimationSpeed.DEFAULT),
                        AbstractPackOverlay::start,
                        AbstractPackOverlay::stop)
                .build();
    }

    /**
     * Creates a fresh overlay instance for a session or preview.
     */
    protected abstract AbstractPackOverlay createOverlay(DoubleSupplier animationSpeed);

    /**
     * Fake shell lines shown in the preview; the last line receives the blinking cursor.
     */
    protected List<String> previewLines() {
        return List.of(
                "$ ssh kortty@retro-01",
                "Welcome to korTTY",
                "retro-01:~$ ls -la",
                "drwxr-xr-x  4 kortty  staff   128 sessions",
                "-rw-r--r--  1 kortty  staff  2048 notes.txt",
                "retro-01:~$ ");
    }
}
