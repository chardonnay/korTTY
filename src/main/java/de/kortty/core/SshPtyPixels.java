package de.kortty.core;

import org.apache.sshd.client.channel.PtyCapableChannelSession;

import java.io.IOException;

/**
 * Puts the terminal's size in pixels on the wire of a {@code pty-req} or {@code window-change} in
 * RFC 4254 order: columns, rows, width in pixels, height in pixels. Servers (OpenSSH, and Apache
 * SSHD's own server side) read the third value as the width ({@code ws_xpixel}) and the fourth as
 * the height ({@code ws_ypixel}).
 *
 * <p>Apache SSHD's client names the two the other way round: {@code pty-req} writes
 * {@code getPtyHeight()} third and {@code getPtyWidth()} fourth, and
 * {@code sendWindowChange(columns, lines, height, width)} writes its {@code height} argument third.
 * Passing the pixels by those names would swap width and height at the server, so every pixel
 * size korTTY sends goes through here.
 */
public final class SshPtyPixels {

    private SshPtyPixels() {
    }

    /** Sets the pixel size the channel's {@code pty-req} (and SSHD's two-argument window change) sends. */
    public static void setPtyPixels(PtyCapableChannelSession channel, int width, int height) {
        channel.setPtyHeight(Math.max(0, width));
        channel.setPtyWidth(Math.max(0, height));
    }

    /** Sends a {@code window-change} with the size in cells and in pixels. */
    public static void sendWindowChange(PtyCapableChannelSession channel, int columns, int rows, int width,
            int height) throws IOException {
        channel.sendWindowChange(columns, rows, Math.max(0, width), Math.max(0, height));
    }
}
