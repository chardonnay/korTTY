package de.kortty.codingagent;

import com.sithtermfx.core.TtyConnector;
import java.util.Optional;

/**
 * What {@link CodingAgentActions} needs from the UI. Implemented by the UI bridge (over the widget's
 * decorated {@code widget.getTtyConnector()}), faked in tests. JavaFX-free by design.
 */
public interface PaneAccess {

    /** The pane's decorated connector, empty when the pane is no longer open. */
    Optional<TtyConnector> connectorFor(PaneRef pane);

    /** True while the application inside the pane has enabled bracketed paste (DECSET 2004). */
    boolean isBracketedPasteEnabled(PaneRef pane);

    /** True when korTTY's own AI shortcut filter would swallow a line starting like {@code firstLine}. */
    boolean wouldHostShortcutIntercept(String firstLine);

    /** The configured AI shortcut command name, for error messages. */
    String hostShortcutCommandName();
}
