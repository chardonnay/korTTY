package de.kortty.core.worker;

import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.server.channel.ChannelSession;

import java.io.IOException;
import java.util.Map;

/**
 * A session channel of the worker's loopback endpoint that remembers the terminal's size in pixels.
 * Apache SSHD parses the pixel width and height of {@code pty-req} and {@code window-change} but
 * keeps only columns and lines in the {@link org.apache.sshd.server.Environment}, so without this
 * {@link RelayCommand} would forward 0×0 upstream and image tools (kitty graphics protocol) on the
 * server could not size their images. The pixels are recorded before SSHD signals {@code WINCH},
 * so the relay's signal listener already sees the new size.
 */
final class PixelSizeChannelSession extends ChannelSession {

    static final ChannelFactory FACTORY = new ChannelFactory() {
        @Override
        public String getName() {
            return "session";
        }

        @Override
        public Channel createChannel(Session session) {
            return new PixelSizeChannelSession();
        }
    };

    private volatile int pixelWidth;
    private volatile int pixelHeight;

    int pixelWidth() {
        return pixelWidth;
    }

    int pixelHeight() {
        return pixelHeight;
    }

    @Override
    protected RequestHandler.Result handlePtyReqParsed(String term, int tColumns, int tRows, int tWidth, int tHeight,
            Map<PtyMode, Integer> ptyModes) throws IOException {
        recordPixels(tWidth, tHeight);
        return super.handlePtyReqParsed(term, tColumns, tRows, tWidth, tHeight, ptyModes);
    }

    @Override
    protected RequestHandler.Result handleWindowChangeParsed(int tColumns, int tRows, int tWidth, int tHeight)
            throws IOException {
        recordPixels(tWidth, tHeight);
        return super.handleWindowChangeParsed(tColumns, tRows, tWidth, tHeight);
    }

    private void recordPixels(int width, int height) {
        pixelWidth = Math.max(0, width);
        pixelHeight = Math.max(0, height);
    }
}
