package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

class ControlLineCodecTest {

    /** Counts the calls a codec makes, so "one line is one write" can be asserted rather than assumed. */
    private static final class CountingOutputStream extends OutputStream {

        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        private int writeCalls;

        private int flushCalls;

        @Override
        public void write(int b) {
            writeCalls++;
            sink.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writeCalls++;
            sink.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushCalls++;
        }

        String written() {
            return sink.toString(StandardCharsets.UTF_8);
        }
    }

    private static ControlLineCodec codec(String input, int maxLineBytes) {
        return codec(input.getBytes(StandardCharsets.UTF_8), maxLineBytes, new CountingOutputStream());
    }

    private static ControlLineCodec codec(byte[] input, int maxLineBytes, OutputStream out) {
        InputStream in = new ByteArrayInputStream(input);
        return new ControlLineCodec(in, out, maxLineBytes);
    }

    @Test(timeOut = 30_000)
    void readsLinesWithAndWithoutACarriageReturn() throws Exception {
        ControlLineCodec codec = codec("{\"a\":1}\n{\"b\":2}\r\n{\"c\":3}", 1024);
        assertThat(codec.readLine()).isEqualTo("{\"a\":1}");
        assertThat(codec.readLine()).isEqualTo("{\"b\":2}");
        assertThat(codec.readLine()).isEqualTo("{\"c\":3}");
        assertThat(codec.readLine()).isNull();
    }

    @Test(timeOut = 30_000)
    void anEmptyStreamReadsAsEndOfStream() throws Exception {
        assertThat(codec("", 1024).readLine()).isNull();
    }

    @Test(timeOut = 30_000)
    void anEmptyLineIsAnEmptyStringRatherThanEndOfStream() throws Exception {
        ControlLineCodec codec = codec("\n\r\nx\n", 1024);
        assertThat(codec.readLine()).isEmpty();
        assertThat(codec.readLine()).isEmpty();
        assertThat(codec.readLine()).isEqualTo("x");
        assertThat(codec.readLine()).isNull();
    }

    @Test(timeOut = 30_000)
    void aLineAtTheCapSucceedsAndOneByteOverIsRejected() throws Exception {
        int cap = 64;
        assertThat(codec("a".repeat(cap) + "\n", cap).readLine()).hasLength(cap);

        ControlLineCodec tooLong = codec("a".repeat(cap + 1) + "\n", cap);
        ControlApiException failure = expectThrows(ControlApiException.class, tooLong::readLine);
        assertThat(failure.code()).isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE);
        assertThat(failure.data()).containsEntry("max_line_bytes", cap);
    }

    @Test(timeOut = 30_000)
    void anUnterminatedLineAtTheCapIsStillRejectedBeforeTheHeapIsExhausted() {
        ControlLineCodec codec = codec("a".repeat(4096), 64);
        ControlApiException failure = expectThrows(ControlApiException.class, codec::readLine);
        assertThat(failure.code()).isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE);
    }

    @Test(timeOut = 30_000)
    void multiByteCharactersAreDecodedAsUtf8AndCountedAsBytes() throws Exception {
        String line = "{\"t\":\"äöü\"}";
        assertThat(codec(line + "\n", 1024).readLine()).isEqualTo(line);

        int bytes = line.getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).isGreaterThan(line.length());
        ControlLineCodec tight = codec(line + "\n", bytes - 1);
        assertThat(expectThrows(ControlApiException.class, tight::readLine).code())
            .isEqualTo(ControlErrorCode.MESSAGE_TOO_LARGE);
    }

    @Test(timeOut = 30_000)
    void writeLineEmitsExactlyOneWriteAndOneFlush() throws Exception {
        CountingOutputStream out = new CountingOutputStream();
        ControlLineCodec codec = codec(new byte[0], 1024, out);

        codec.writeLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        assertThat(out.writeCalls).isEqualTo(1);
        assertThat(out.flushCalls).isEqualTo(1);
        assertThat(out.written()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n");
    }

    @Test(timeOut = 30_000)
    void writeLineNeverDoublesATrailingNewline() throws Exception {
        CountingOutputStream out = new CountingOutputStream();
        ControlLineCodec codec = codec(new byte[0], 1024, out);

        codec.writeLine(ControlFrame.result(null, new JsonObject()).toLine());
        assertThat(out.writeCalls).isEqualTo(1);
        assertThat(out.written()).endsWith("}\n");
        assertThat(out.written()).doesNotContain("\n\n");
    }

    @Test(timeOut = 30_000)
    void aNonPositiveCapIsRejectedAtConstruction() {
        expectThrows(IllegalArgumentException.class,
            () -> new ControlLineCodec(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), 0));
    }

    @Test(timeOut = 30_000)
    void aFailingStreamSurfacesAsAnIoException() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("socket closed");
            }
        };
        ControlLineCodec codec = new ControlLineCodec(broken, new ByteArrayOutputStream(), 1024);
        expectThrows(IOException.class, codec::readLine);
    }
}
