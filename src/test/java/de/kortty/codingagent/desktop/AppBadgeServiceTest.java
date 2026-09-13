package de.kortty.codingagent.desktop;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;

class AppBadgeServiceTest {

    /** Records every count and attention request; can be switched to unsupported or throwing. */
    static final class FakeBadgeBackend implements AppBadgeBackend {
        final List<Integer> counts = new ArrayList<>();
        int attentionRequests;
        int closeCalls;
        boolean supported = true;
        boolean throwOnShow;
        boolean throwOnClose;

        @Override
        public boolean isSupported() {
            return supported;
        }

        @Override
        public void showCount(int blockedCount) throws IOException {
            if (throwOnShow) {
                throw new IOException("badge failed");
            }
            counts.add(blockedCount);
        }

        @Override
        public void requestAttention() {
            attentionRequests++;
        }

        @Override
        public void close() {
            closeCalls++;
            if (throwOnClose) {
                throw new IllegalStateException("close failed");
            }
        }
    }

    static final class FakeTitlePresenter implements TitleBadgePresenter {
        final List<Integer> counts = new ArrayList<>();

        @Override
        public void applyTitleCount(int blockedCount) {
            counts.add(blockedCount);
        }
    }

    @Test
    void appliesEachChangeOnceAndCoalescesEqualCounts() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);

        service.update(1, false);
        service.update(1, false);
        service.update(2, false);
        service.update(2, false);
        service.update(0, false);
        service.update(0, false);

        assertThat(backend.counts).containsExactly(1, 2, 0).inOrder();
        assertThat(title.counts).isEmpty();
        assertThat(service.lastAppliedCount()).isEqualTo(0);
        assertThat(service.usingTitleFallback()).isFalse();
    }

    @Test
    void urgentRequestsAttentionOnlyForAPositiveCount() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        AppBadgeService service = new AppBadgeService(backend, new FakeTitlePresenter(), () -> true);

        service.update(0, true);
        assertThat(backend.attentionRequests).isEqualTo(0);

        service.update(1, true);
        assertThat(backend.attentionRequests).isEqualTo(1);

        service.update(1, true);
        assertThat(backend.attentionRequests).isEqualTo(2);

        service.update(2, false);
        assertThat(backend.attentionRequests).isEqualTo(2);
        assertThat(backend.counts).containsExactly(0, 1, 2).inOrder();
    }

    @Test
    void disabledSettingClearsOnceThenDoesNothing() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        AppBadgeService service = new AppBadgeService(backend, new FakeTitlePresenter(), () -> false);

        service.update(3, true);
        service.update(5, true);
        service.update(0, false);

        assertThat(backend.counts).containsExactly(0);
        assertThat(backend.attentionRequests).isEqualTo(0);
        assertThat(service.lastAppliedCount()).isEqualTo(0);
    }

    @Test
    void reEnablingTheSettingAppliesTheLastRequestedCountOnRefresh() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        AtomicBoolean enabled = new AtomicBoolean(false);
        AppBadgeService service = new AppBadgeService(backend, new FakeTitlePresenter(), enabled::get);

        service.update(4, false);
        assertThat(backend.counts).containsExactly(0);

        enabled.set(true);
        service.refresh();
        assertThat(backend.counts).containsExactly(0, 4).inOrder();

        enabled.set(false);
        service.refresh();
        assertThat(backend.counts).containsExactly(0, 4, 0).inOrder();
    }

    @Test
    void unsupportedBackendUsesTheTitlePresenter() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        backend.supported = false;
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);

        service.update(2, true);
        service.update(2, true);
        service.update(0, false);

        assertThat(service.usingTitleFallback()).isTrue();
        assertThat(title.counts).containsExactly(2, 0).inOrder();
        assertThat(backend.counts).isEmpty();
        assertThat(backend.attentionRequests).isEqualTo(0);
    }

    @Test
    void backendThrowingTwiceDegradesToTheTitleFallback() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        backend.throwOnShow = true;
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);

        service.update(1, false);
        assertThat(service.usingTitleFallback()).isFalse();
        assertThat(title.counts).isEmpty();

        service.update(2, false);
        assertThat(service.usingTitleFallback()).isTrue();
        assertThat(title.counts).containsExactly(2);

        backend.throwOnShow = false;
        service.update(3, false);
        assertThat(title.counts).containsExactly(2, 3).inOrder();
        assertThat(backend.counts).isEmpty();
    }

    @Test
    void singleFailureFollowedBySuccessDoesNotDegrade() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        backend.throwOnShow = true;
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);

        service.update(1, false);
        backend.throwOnShow = false;
        service.update(2, false);
        backend.throwOnShow = true;
        service.update(3, false);

        assertThat(service.usingTitleFallback()).isFalse();
        assertThat(backend.counts).containsExactly(2);
        assertThat(title.counts).isEmpty();
    }

    @Test
    void anUnchangedCountIsReAppliedWhenTheBackendGivesUpAsynchronously() {
        // The Linux backend emits on its own executor and reports the failure only afterwards, so the
        // count that caused it must still reach the title even though it never changes.
        FakeBadgeBackend backend = new FakeBadgeBackend();
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);

        service.update(1, false);
        assertThat(backend.counts).containsExactly(1);
        assertThat(title.counts).isEmpty();

        backend.supported = false;
        service.update(1, false);

        assertThat(service.usingTitleFallback()).isTrue();
        assertThat(title.counts).containsExactly(1);
        assertThat(backend.counts).containsExactly(1);

        // Once the fallback is active an unchanged count is coalesced again.
        service.update(1, false);
        assertThat(title.counts).containsExactly(1);
    }

    @Test
    void refreshReappliesTheCurrentCount() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);

        service.refresh();
        assertThat(backend.counts).containsExactly(0);

        service.update(2, false);
        service.refresh();
        assertThat(backend.counts).containsExactly(0, 2, 2).inOrder();

        backend.supported = false;
        service.refresh();
        assertThat(title.counts).containsExactly(2);
        assertThat(backend.counts).containsExactly(0, 2, 2).inOrder();
    }

    @Test
    void closeClearsBestEffortAndIgnoresLaterUpdates() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        backend.throwOnClose = true;
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);
        service.update(2, false);

        service.close();
        service.close();
        service.update(5, true);
        service.refresh();

        assertThat(backend.closeCalls).isEqualTo(1);
        assertThat(backend.counts).containsExactly(2);
        assertThat(backend.attentionRequests).isEqualTo(0);
    }

    @Test
    void closeRestoresThePlainTitleWhenTheFallbackShowedACount() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        backend.supported = false;
        FakeTitlePresenter title = new FakeTitlePresenter();
        AppBadgeService service = new AppBadgeService(backend, title, () -> true);
        service.update(3, false);

        service.close();

        assertThat(title.counts).containsExactly(3, 0).inOrder();
        assertThat(backend.closeCalls).isEqualTo(1);
    }

    @Test
    void negativeCountsAreTreatedAsZero() {
        FakeBadgeBackend backend = new FakeBadgeBackend();
        AppBadgeService service = new AppBadgeService(backend, new FakeTitlePresenter(), () -> true);

        service.update(-3, true);

        assertThat(backend.counts).containsExactly(0);
        assertThat(backend.attentionRequests).isEqualTo(0);
    }

    @Test
    void badgeTextCapsAtNinePlus() {
        assertThat(BadgeIconRenderer.badgeText(0)).isEmpty();
        assertThat(BadgeIconRenderer.badgeText(1)).isEqualTo("1");
        assertThat(BadgeIconRenderer.badgeText(9)).isEqualTo("9");
        assertThat(BadgeIconRenderer.badgeText(10)).isEqualTo("9+");
        assertThat(BadgeIconRenderer.badgeText(120)).isEqualTo("9+");
    }
}
