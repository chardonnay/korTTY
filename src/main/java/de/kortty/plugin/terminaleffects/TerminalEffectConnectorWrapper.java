package de.kortty.plugin.terminaleffects;

import com.sithtermfx.core.TtyConnector;

/**
 * Marker for connector wrappers created by terminal effects.
 */
public interface TerminalEffectConnectorWrapper extends TtyConnector {

    TtyConnector delegate();
}
