package de.kortty.core;

import org.testng.annotations.Test;

import java.util.EnumSet;

import static com.google.common.truth.Truth.assertThat;

public class TerminalPathTokenTest {

    @Test
    public void lineAndColumnSuffixesAreSplitOff() {
        assertThat(TerminalPathToken.parse("src/Foo.java:12")).isEqualTo(new TerminalPathToken("src/Foo.java", 12, 0));
        assertThat(TerminalPathToken.parse("src/Foo.java:12:5")).isEqualTo(new TerminalPathToken("src/Foo.java", 12, 5));
        assertThat(TerminalPathToken.parse("src/App/Foo.cs(12,5)")).isEqualTo(new TerminalPathToken("src/App/Foo.cs", 12, 5));
        assertThat(TerminalPathToken.parse("/var/log/syslog:120")).isEqualTo(new TerminalPathToken("/var/log/syslog", 120, 0));
    }

    @Test
    public void aPathWithoutSuffixIsKeptWhole() {
        TerminalPathToken token = TerminalPathToken.parse("/etc/hosts");
        assertThat(token).isEqualTo(new TerminalPathToken("/etc/hosts", TerminalPathToken.NONE, TerminalPathToken.NONE));
        assertThat(token.hasLine()).isFalse();
        assertThat(token.hasColumn()).isFalse();
        assertThat(TerminalPathToken.parse("~/notes.txt:").path()).isEqualTo("~/notes.txt:");
        assertThat(TerminalPathToken.parse("a/b.txt:x").path()).isEqualTo("a/b.txt:x");
        assertThat(TerminalPathToken.parse("a/b(1).txt").path()).isEqualTo("a/b(1).txt");
    }

    @Test
    public void windowsDriveLettersAreNotTakenForALineNumber() {
        assertThat(TerminalPathToken.parse("C:\\x\\y")).isEqualTo(new TerminalPathToken("C:\\x\\y", 0, 0));
        assertThat(TerminalPathToken.parse("C:/x")).isEqualTo(new TerminalPathToken("C:/x", 0, 0));
        assertThat(TerminalPathToken.parse("C:12")).isEqualTo(new TerminalPathToken("C:12", 0, 0));
        assertThat(TerminalPathToken.parse("C:\\x\\y:12")).isEqualTo(new TerminalPathToken("C:\\x\\y", 12, 0));
        assertThat(TerminalPathToken.parse("C:\\x\\y:12:5")).isEqualTo(new TerminalPathToken("C:\\x\\y", 12, 5));
        assertThat(TerminalPathToken.parse("D:/src/Foo.cs(3,9)")).isEqualTo(new TerminalPathToken("D:/src/Foo.cs", 3, 9));
    }

    @Test
    public void lineZeroAndOverlongNumbersCarryNoPosition() {
        TerminalPathToken zero = TerminalPathToken.parse("a/b.txt:0");
        assertThat(zero.path()).isEqualTo("a/b.txt");
        assertThat(zero.hasLine()).isFalse();
        assertThat(TerminalPathToken.parse("a/b.txt:1234567890")).isEqualTo(new TerminalPathToken("a/b.txt:1234567890", 0, 0));
        assertThat(TerminalPathToken.parse(":12").path()).isEqualTo(":12");
        assertThat(TerminalPathToken.parse("(1,2)").path()).isEqualTo("(1,2)");
    }

    @Test
    public void detectedPathsRoundTrip() {
        String text = TerminalLinkDetector.find("src/main/Foo.java:42:7: error: ';' expected",
            EnumSet.of(TerminalLinkDetector.Kind.PATH)).getFirst().text();
        TerminalPathToken token = TerminalPathToken.parse(text);
        assertThat(token).isEqualTo(new TerminalPathToken("src/main/Foo.java", 42, 7));
        assertThat(token.hasLine()).isTrue();
        assertThat(token.hasColumn()).isTrue();
    }
}
