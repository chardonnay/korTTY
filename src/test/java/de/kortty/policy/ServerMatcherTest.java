package de.kortty.policy;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class ServerMatcherTest {

    @Test
    void matchesExactHostnameCaseInsensitively() {
        ServerMatcher matcher = ServerMatcher.parse("DB01.acme.com");
        assertThat(matcher.matches("db01.acme.com", 22)).isTrue();
        assertThat(matcher.matches("DB01.ACME.COM", 2222)).isTrue();
        assertThat(matcher.matches("db02.acme.com", 22)).isFalse();
    }

    @Test
    void matchesHostnameGlobs() {
        ServerMatcher star = ServerMatcher.parse("*.prod.acme.com");
        assertThat(star.matches("web1.prod.acme.com", 22)).isTrue();
        assertThat(star.matches("a.b.prod.acme.com", 22)).isTrue();
        assertThat(star.matches("web1.dev.acme.com", 22)).isFalse();
        assertThat(star.matches("prod.acme.com", 22)).isFalse();

        ServerMatcher question = ServerMatcher.parse("web-??.acme.com");
        assertThat(question.matches("web-01.acme.com", 22)).isTrue();
        assertThat(question.matches("web-1.acme.com", 22)).isFalse();
    }

    @Test
    void portSuffixRestrictsThePort() {
        ServerMatcher matcher = ServerMatcher.parse("vault.acme.com:2222");
        assertThat(matcher.matches("vault.acme.com", 2222)).isTrue();
        assertThat(matcher.matches("vault.acme.com", 22)).isFalse();
        assertThat(ServerMatcher.parse("vault.acme.com").matches("vault.acme.com", 2222)).isTrue();
    }

    @Test
    void matchesSingleIpv4WithOptionalPort() {
        ServerMatcher matcher = ServerMatcher.parse("192.168.10.42");
        assertThat(matcher.matches("192.168.10.42", 22)).isTrue();
        assertThat(matcher.matches("192.168.10.43", 22)).isFalse();

        ServerMatcher withPort = ServerMatcher.parse("192.168.10.42:22");
        assertThat(withPort.matches("192.168.10.42", 22)).isTrue();
        assertThat(withPort.matches("192.168.10.42", 23)).isFalse();
    }

    @Test
    void matchesSingleIpv6IncludingBracketPortForm() {
        ServerMatcher matcher = ServerMatcher.parse("2001:db8::1");
        assertThat(matcher.matches("2001:db8::1", 22)).isTrue();
        assertThat(matcher.matches("2001:db8:0:0:0:0:0:1", 22)).isTrue();
        assertThat(matcher.matches("2001:db8::2", 22)).isFalse();

        ServerMatcher withPort = ServerMatcher.parse("[2001:db8::1]:22");
        assertThat(withPort.matches("2001:db8::1", 22)).isTrue();
        assertThat(withPort.matches("2001:db8::1", 23)).isFalse();
        assertThat(withPort.matches("[2001:db8::1]", 22)).isTrue();

        // Without brackets a trailing :22 is part of the IPv6 address, not a port.
        ServerMatcher ambiguous = ServerMatcher.parse("2001:db8::1:22");
        assertThat(ambiguous.matches("2001:db8::1:22", 4711)).isTrue();
        assertThat(ambiguous.matches("2001:db8::1", 22)).isFalse();
    }

    @Test
    void matchesCidrNetworks() {
        ServerMatcher v4 = ServerMatcher.parse("10.99.0.0/16");
        assertThat(v4.matches("10.99.1.2", 22)).isTrue();
        assertThat(v4.matches("10.100.0.1", 22)).isFalse();

        ServerMatcher oddPrefix = ServerMatcher.parse("10.0.0.0/9");
        assertThat(oddPrefix.matches("10.127.0.1", 22)).isTrue();
        assertThat(oddPrefix.matches("10.128.0.1", 22)).isFalse();

        ServerMatcher v6 = ServerMatcher.parse("2001:db8::/32");
        assertThat(v6.matches("2001:db8:1234::1", 22)).isTrue();
        assertThat(v6.matches("2001:db9::1", 22)).isFalse();
    }

    @Test
    void matchesInclusiveIpRanges() {
        ServerMatcher range = ServerMatcher.parse("10.20.0.100-10.20.0.199");
        assertThat(range.matches("10.20.0.100", 22)).isTrue();
        assertThat(range.matches("10.20.0.150", 22)).isTrue();
        assertThat(range.matches("10.20.0.199", 22)).isTrue();
        assertThat(range.matches("10.20.0.99", 22)).isFalse();
        assertThat(range.matches("10.20.0.200", 22)).isFalse();
    }

    @Test
    void hostnamesAndIpLiteralsAreDistinctNamespaces() {
        // A glob never matches an IP-literal host, and an IP pattern never matches a hostname.
        assertThat(ServerMatcher.parse("10.*").matches("10.2.3.4", 22)).isFalse();
        assertThat(ServerMatcher.parse("10.0.0.0/8").matches("ten.acme.com", 22)).isFalse();
        // IPv4 patterns do not match plain IPv6 hosts …
        assertThat(ServerMatcher.parse("10.0.0.0/8").matches("2001:db8::1", 22)).isFalse();
        // … but an IPv4-mapped IPv6 literal normalizes to its IPv4 form and IS matched — otherwise
        // ::ffff:10.1.2.3 would bypass a deny rule for 10.0.0.0/8.
        assertThat(ServerMatcher.parse("10.0.0.0/8").matches("::ffff:10.1.2.3", 22)).isTrue();
    }

    @Test
    void hostnameWithDashIsNotMistakenForIpRange() {
        ServerMatcher matcher = ServerMatcher.parse("web-01.acme.com");
        assertThat(matcher.matches("web-01.acme.com", 22)).isTrue();
    }

    @Test
    void rejectsMalformedPatterns() {
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse(""));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("  "));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("host:99999"));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("host:abc"));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("10.0.0.0/33"));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("10.0.0.0/-1"));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("10.0.0.0/x"));
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("notanip/8"));
        // Reversed range bounds.
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("10.0.0.9-10.0.0.1"));
        // Mixed address families in a range.
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("10.0.0.1-2001:db8::1"));
        // Multi-colon strings that are not valid IPv6.
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("host:22:extra"));
        // Unclosed bracket.
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("[2001:db8::1"));
        // Illegal characters in a hostname.
        expectThrows(IllegalArgumentException.class, () -> ServerMatcher.parse("bad host"));
    }

    @Test
    void neverMatchesBlankHosts() {
        ServerMatcher matcher = ServerMatcher.parse("*");
        assertThat(matcher.matches(null, 22)).isFalse();
        assertThat(matcher.matches("  ", 22)).isFalse();
    }
}
