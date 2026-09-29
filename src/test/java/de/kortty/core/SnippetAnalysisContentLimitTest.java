package de.kortty.core;

import de.kortty.core.SnippetAnalysisContentLimit.Limits;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** The effective stored-content limit: defaults, user value, policy clamp, boundaries and UTF-8 sizing. */
public class SnippetAnalysisContentLimitTest {

    private static final long KB = 1024L;
    private static final long MB = 1024L * KB;

    @AfterMethod(alwaysRun = true)
    public void resetLimits() {
        SnippetAnalysisContentLimit.reset();
    }

    @Test
    public void defaultIsOneMebibyteOfUtf8AndTheHardMaximumIsFive() {
        assertThat(SnippetAnalysisContentLimit.DEFAULT_BYTES).isEqualTo(1 * MB);
        assertThat(SnippetAnalysisContentLimit.MAX_BYTES).isEqualTo(5 * MB);
        assertThat(SnippetAnalysisContentLimit.MIN_BYTES).isEqualTo(256 * KB);
        assertThat(SnippetAnalysisContentLimit.current()).isEqualTo(1 * MB);
        Limits limits = SnippetAnalysisContentLimit.compute(null, null);
        assertThat(limits.effective()).isEqualTo(1 * MB);
        assertThat(limits.ceiling()).isEqualTo(5 * MB);
        assertThat(limits.policyMax()).isNull();
        assertThat(limits.forbiddenByPolicy()).isFalse();
    }

    @Test
    public void userValueIsNormalised() {
        assertThat(SnippetAnalysisContentLimit.normalizeUser(null)).isEqualTo(1 * MB);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(-7L)).isEqualTo(1 * MB);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(0L)).isEqualTo(0L);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(1L)).isEqualTo(256 * KB);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(3 * MB)).isEqualTo(3 * MB);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(5 * MB)).isEqualTo(5 * MB);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(5 * MB + 1)).isEqualTo(5 * MB);
        assertThat(SnippetAnalysisContentLimit.normalizeUser(Long.MAX_VALUE)).isEqualTo(5 * MB);
    }

    @Test
    public void effectiveIsTheSmallerOfUserAndPolicy() {
        assertThat(SnippetAnalysisContentLimit.compute(4 * MB, null).effective()).isEqualTo(4 * MB);
        assertThat(SnippetAnalysisContentLimit.compute(4 * MB, 2 * MB).effective()).isEqualTo(2 * MB);
        assertThat(SnippetAnalysisContentLimit.compute(512 * KB, 2 * MB).effective()).isEqualTo(512 * KB);
        // an admin may cap below the user minimum
        assertThat(SnippetAnalysisContentLimit.compute(2 * MB, 1000L).effective()).isEqualTo(1000L);
        // a policy above the user's own value never raises it, and never exceeds the hard maximum
        assertThat(SnippetAnalysisContentLimit.compute(1 * MB, 500 * MB).effective()).isEqualTo(1 * MB);
        assertThat(SnippetAnalysisContentLimit.compute(500 * MB, 500 * MB).effective()).isEqualTo(5 * MB);
    }

    @Test
    public void policyZeroForbidsStoringScriptText() {
        Limits limits = SnippetAnalysisContentLimit.compute(4 * MB, 0L);
        assertThat(limits.effective()).isEqualTo(0L);
        assertThat(limits.ceiling()).isEqualTo(0L);
        assertThat(limits.forbiddenByPolicy()).isTrue();
        // a user who turned it off themselves is not "forbidden by policy"
        Limits off = SnippetAnalysisContentLimit.compute(0L, null);
        assertThat(off.effective()).isEqualTo(0L);
        assertThat(off.forbiddenByPolicy()).isFalse();
    }

    @Test
    public void invalidPolicyValuesFallBackToNoPolicy() {
        assertThat(SnippetAnalysisContentLimit.compute(3 * MB, -1L).effective()).isEqualTo(3 * MB);
        assertThat(SnippetAnalysisContentLimit.compute(3 * MB, -1L).policyMax()).isNull();
    }

    @Test
    public void ceilingKeepsStoredContentWhenTheUserLowersTheLimitButFollowsAPolicy() {
        // user lowered to 256 KB: stored text up to the hard maximum stays
        assertThat(SnippetAnalysisContentLimit.compute(256 * KB, null).ceiling()).isEqualTo(5 * MB);
        // the administrator's cap is also the ceiling
        assertThat(SnippetAnalysisContentLimit.compute(256 * KB, 2 * MB).ceiling()).isEqualTo(2 * MB);
    }

    @Test
    public void installedLimitsAreReadLiveAndABrokenSupplierReadsAsDefault() {
        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(2 * MB, null));
        assertThat(SnippetAnalysisContentLimit.current()).isEqualTo(2 * MB);
        SnippetAnalysisContentLimit.install(() -> {
            throw new IllegalStateException("boom");
        });
        assertThat(SnippetAnalysisContentLimit.current()).isEqualTo(1 * MB);
        SnippetAnalysisContentLimit.install(null);
        assertThat(SnippetAnalysisContentLimit.current()).isEqualTo(1 * MB);
    }

    @Test
    public void textOfExactlyTheLimitFitsAndOneByteMoreDoesNot() {
        long limit = 100_000;
        assertThat(SnippetAnalysisContentLimit.fits("x".repeat((int) limit), limit)).isTrue();
        assertThat(SnippetAnalysisContentLimit.fits("x".repeat((int) limit + 1), limit)).isFalse();
        assertThat(SnippetAnalysisContentLimit.fits("", limit)).isTrue();
        assertThat(SnippetAnalysisContentLimit.fits(null, limit)).isFalse();
        assertThat(SnippetAnalysisContentLimit.fits("x", 0)).isFalse();
    }

    @Test
    public void limitCountsBytesOfUtf8NotChars() {
        long limit = 100_000;
        // 2-byte chars: 50_000 chars = exactly 100_000 bytes
        assertThat(SnippetAnalysisContentLimit.fits("ä".repeat(50_000), limit)).isTrue();
        assertThat(SnippetAnalysisContentLimit.fits("ä".repeat(50_001), limit)).isFalse();
        // 3-byte chars
        assertThat(SnippetAnalysisContentLimit.fits("€".repeat(33_333), limit)).isTrue();
        assertThat(SnippetAnalysisContentLimit.fits("€".repeat(33_334), limit)).isFalse();
        // 4-byte chars (surrogate pairs)
        assertThat(SnippetAnalysisContentLimit.fits("😀".repeat(25_000), limit)).isTrue();
        assertThat(SnippetAnalysisContentLimit.fits("😀".repeat(25_001), limit)).isFalse();
    }

    @Test
    public void utf8LengthMatchesTheEncoder() {
        String mixed = ("abc äöü € 😀 line\n").repeat(30_000);
        assertThat(mixed.length()).isGreaterThan(64 * 1024);
        assertThat(SnippetAnalysisContentLimit.utf8Length(mixed))
            .isEqualTo(SnippetAnalysisContentLimit.referenceUtf8Length(mixed));
        // cached second call
        assertThat(SnippetAnalysisContentLimit.utf8Length(mixed))
            .isEqualTo(SnippetAnalysisContentLimit.referenceUtf8Length(mixed));
        String shortMixed = "äö€😀";
        assertThat(SnippetAnalysisContentLimit.utf8Length(shortMixed))
            .isEqualTo(SnippetAnalysisContentLimit.referenceUtf8Length(shortMixed));
    }

    @Test
    public void capContentUsesTheInstalledLimit() {
        SnippetAnalysisContentLimit.install(
            () -> SnippetAnalysisContentLimit.compute(SnippetAnalysisContentLimit.MIN_BYTES, null));
        String atLimit = "y".repeat((int) SnippetAnalysisContentLimit.MIN_BYTES);
        assertThat(SnippetAnalysisRecord.capContent(atLimit)).isSameInstanceAs(atLimit);
        assertThat(SnippetAnalysisRecord.capContent(atLimit + "y")).isNull();
        assertThat(SnippetAnalysisRecord.capContent(null)).isNull();

        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(2 * MB, 0L));
        assertThat(SnippetAnalysisRecord.capContent("tiny")).isNull();
    }

    @Test
    public void capContentAtTheDefaultStoresOneMebibyteAndDropsOneByteMore() {
        String exact = "z".repeat((int) SnippetAnalysisContentLimit.DEFAULT_BYTES);
        assertThat(SnippetAnalysisRecord.capContent(exact)).isSameInstanceAs(exact);
        assertThat(SnippetAnalysisRecord.capContent(exact + "z")).isNull();
    }

    @Test
    public void fileLimitIsIndependentOfTheSettingSoLoweringNeverOrphansAFile() {
        // 12 x the 5 MiB hard maximum
        assertThat(SnippetAnalysisContentLimit.maxFileBytes()).isEqualTo(60 * MB);
        SnippetAnalysisContentLimit.install(() -> SnippetAnalysisContentLimit.compute(256 * KB, null));
        assertThat(SnippetAnalysisContentLimit.maxFileBytes()).isEqualTo(60 * MB);
        assertThat(SnippetAnalysisContentLimit.contentBudgetBytes()).isEqualTo(30 * MB);
        assertThat(SnippetAnalysisStore.maxFileBytes()).isEqualTo(60 * MB);
        SnippetAnalysisContentLimit.overrideMaxFileBytesForTests(1 * MB);
        assertThat(SnippetAnalysisContentLimit.maxFileBytes()).isEqualTo(1 * MB);
        assertThat(SnippetAnalysisContentLimit.contentBudgetBytes()).isEqualTo(512 * KB);
        SnippetAnalysisContentLimit.reset();
        assertThat(SnippetAnalysisContentLimit.maxFileBytes()).isEqualTo(60 * MB);
    }
}
