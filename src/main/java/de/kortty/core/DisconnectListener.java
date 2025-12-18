package de.kortty.core;

/**
 * Listener for SSH connection disconnect events.
 */
public interface DisconnectListener {
    
    /**
     * Called when the SSH connection is disconnected.
     * 
     * @param reason The reason for disconnection (e.g., "Normal exit", "Connection error")
     * @param wasError True if the disconnect was caused by an error, false for normal disconnect
     */
    void onDisconnect(String reason, boolean wasError);
}
