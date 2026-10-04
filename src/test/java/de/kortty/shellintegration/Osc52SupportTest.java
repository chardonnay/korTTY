package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.Osc52Support.Decoded;
import de.kortty.shellintegration.Osc52Support.Rejection;
import de.kortty.shellintegration.ShellIntegrationEvent.ClipboardWrite;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.testng.annotations.Test;

/**
 * What korTTY accepts from a program that writes the clipboard with {@code OSC 52}: xterm's selection
 * parameters, MIME-wrapped base64 of UTF-8 text up to 256 KiB, and never a query.
 */
class Osc52SupportTest {

    @Test
    void everySelectionXtermKnowsIsAWriteToTheOneClipboard() {
        for (String selection : List.of("", "c", "p", "q", "s", "0", "1", "2", "3", "4", "5", "6", "7", "cs", "pc0")) {
            assertWithMessage(selection).that(Osc52Support.parse(selection + ";" + base64("copied")))
                .isEqualTo(new ClipboardWrite(selection, base64("copied")));
            assertWithMessage(selection).that(Osc52Support.isSelection(selection)).isTrue();
        }
        for (String selection : List.of("x", "8", "C", "c ", "+", "*", "c;")) {
            assertWithMessage(selection).that(Osc52Support.isSelection(selection)).isFalse();
        }
        assertThat(Osc52Support.parse("x;" + base64("copied"))).isNull();
        assertWithMessage("the data ends at the terminator, so a further ';' belongs to it")
            .that(Osc52Support.parse("c;a;b")).isEqualTo(new ClipboardWrite("c", "a;b"));
    }

    @Test
    void aQueryIsNeverAWrite() {
        // Answering would hand the clipboard to whatever program runs in the terminal.
        assertThat(Osc52Support.parse("c;?")).isNull();
        assertThat(Osc52Support.parse(";?")).isNull();
        assertThat(Osc52Support.parse("p;?")).isNull();
    }

    @Test
    void anEmptyWriteOrOneWithoutSeparatorIsIgnored() {
        assertThat(Osc52Support.parse("c;")).isNull();
        assertThat(Osc52Support.parse(";")).isNull();
        assertThat(Osc52Support.parse("")).isNull();
        assertThat(Osc52Support.parse(base64("copied"))).isNull();
        assertThat(Osc52Support.decode(" \r\n\t").rejection()).isEqualTo(Rejection.EMPTY);
    }

    @Test
    void decodesUtf8TextFromStrictBase64() {
        String text = "grüße, 日本, 🙂\nzweite Zeile\t$HOME";
        Decoded decoded = Osc52Support.decode(base64(text));
        assertThat(decoded.text()).isEqualTo(text);
        assertThat(decoded.bytes()).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
        assertThat(decoded.rejection()).isNull();

        assertWithMessage("padding may be left out, as some encoders do")
            .that(Osc52Support.decode("YQ").text()).isEqualTo("a");
    }

    @Test
    void lineBreaksAndBlanksOfWrappedBase64AreRemovedFirst() {
        String text = "x".repeat(200);
        String mime = Base64.getMimeEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        assertThat(mime).contains("\r\n");
        assertThat(Osc52Support.decode(mime).text()).isEqualTo(text);
        assertThat(Osc52Support.decode(mime.replace("\r\n", "\n")).text()).isEqualTo(text);
        assertThat(Osc52Support.decode(" " + base64("ab") + " \n").text()).isEqualTo("ab");
    }

    @Test
    void invalidBase64IsRejected() {
        for (String data : List.of("not base64!", "YWJj*", "YQ=a", "Y", "YW=", "Y29w-aWVk", "Y29w_aWVk", "%%%%")) {
            assertWithMessage(data).that(Osc52Support.decode(data).rejection()).isEqualTo(Rejection.NOT_BASE64);
            assertWithMessage(data).that(Osc52Support.decode(data).text()).isNull();
        }
    }

    @Test
    void bytesThatAreNotUtf8AreRejected() {
        List<byte[]> notUtf8 = List.of(
            new byte[] {(byte) 0xFF, 'a'},
            new byte[] {(byte) 0xC3},                               // cut off in the middle of a char
            new byte[] {(byte) 0xC0, (byte) 0xAF},                  // overlong '/'
            new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80},     // an encoded surrogate
            "grüße".getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] bytes : notUtf8) {
            assertWithMessage(Arrays.toString(bytes))
                .that(Osc52Support.decode(Base64.getEncoder().encodeToString(bytes)).rejection())
                .isEqualTo(Rejection.NOT_UTF8);
        }
    }

    @Test
    void atMost256KibOfTextAreAccepted() {
        byte[] atCap = new byte[Osc52Support.MAX_DECODED_BYTES];
        Arrays.fill(atCap, (byte) 'a');
        Decoded decoded = Osc52Support.decode(Base64.getMimeEncoder().encodeToString(atCap));
        assertThat(decoded.bytes()).isEqualTo(256 * 1024);
        assertThat(decoded.text()).hasLength(256 * 1024);

        byte[] overCap = Arrays.copyOf(atCap, atCap.length + 1);
        overCap[atCap.length] = 'a';
        assertThat(Osc52Support.decode(Base64.getEncoder().encodeToString(overCap)).rejection())
            .isEqualTo(Rejection.TOO_LARGE);
        assertWithMessage("an unpadded form that decodes to two bytes more than the cap")
            .that(Osc52Support.decode("A".repeat(Osc52Support.MAX_ENCODED_CHARS)).rejection())
            .isEqualTo(Rejection.TOO_LARGE);
        assertWithMessage("far more is refused without decoding it")
            .that(Osc52Support.decode("A".repeat(Osc52Support.MAX_ENCODED_CHARS * 2)).rejection())
            .isEqualTo(Rejection.TOO_LARGE);
    }

    @Test
    void aDecodedWriteHasEitherTextOrARejection() {
        Decoded rejected = Osc52Support.decode("!");
        assertThat(rejected.text()).isNull();
        assertThat(rejected.bytes()).isEqualTo(0);
        try {
            new Decoded("text", 4, Rejection.EMPTY);
            throw new AssertionError("text and a rejection");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    void theSlotHandsOnTheNewestWriteAndSchedulesOneTakeForABurst() {
        ClipboardWriteSlot slot = new ClipboardWriteSlot();
        assertThat(slot.take()).isNull();

        List<Boolean> scheduled = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            scheduled.add(slot.offer(new ClipboardWrite("c", base64("copy " + i))));
        }
        assertWithMessage("only the first write of a burst schedules the UI thread")
            .that(scheduled.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertWithMessage("the clipboard ends up with the burst's last text")
            .that(slot.take()).isEqualTo(new ClipboardWrite("c", base64("copy 9999")));
        assertThat(slot.take()).isNull();
        assertWithMessage("once taken, the next write schedules again")
            .that(slot.offer(new ClipboardWrite("", base64("next")))).isTrue();
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
