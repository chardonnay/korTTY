package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class SettingsAiUsageRecorderTest {

    private static GlobalSettings settingsWith(AiProfile profile) {
        GlobalSettings settings = new GlobalSettings();
        settings.getAiProfiles().add(profile);
        return settings;
    }

    private static AiProfile profile(String id) {
        AiProfile profile = new AiProfile();
        profile.setId(id);
        profile.setName("Profile " + id);
        return profile;
    }

    @Test
    void booksUsageOnTheStoredProfileEvenWhenGivenADetachedCopy() {
        AiProfile stored = profile("a");
        GlobalSettings settings = settingsWith(stored);
        AtomicInteger saves = new AtomicInteger();
        SettingsAiUsageRecorder recorder = new SettingsAiUsageRecorder(() -> settings, saves::incrementAndGet);

        recorder.record(new AiProfile(stored), new AiTokenUsage(100, 40, 140));
        recorder.record(new AiProfile(stored), new AiTokenUsage(10, 5, 15));

        assertThat(stored.getUsedPromptTokens()).isEqualTo(110L);
        assertThat(stored.getUsedCompletionTokens()).isEqualTo(45L);
        assertThat(stored.getUsedTotalTokens()).isEqualTo(155L);
        assertThat(saves.get()).isEqualTo(2);
    }

    @Test
    void ignoresUnknownProfilesAndEmptyUsage() {
        AiProfile stored = profile("a");
        AtomicInteger saves = new AtomicInteger();
        SettingsAiUsageRecorder recorder =
            new SettingsAiUsageRecorder(() -> settingsWith(stored), saves::incrementAndGet);

        recorder.record(profile("missing"), new AiTokenUsage(1, 1, 2));
        recorder.record(stored, new AiTokenUsage(0, 0, 0));
        recorder.record(null, new AiTokenUsage(1, 1, 2));
        recorder.record(stored, null);

        assertThat(stored.getUsedTotalTokens()).isEqualTo(0L);
        assertThat(saves.get()).isEqualTo(0);
    }

    @Test
    void notifiesListenersWithTheStoredProfile() {
        AiProfile stored = profile("a");
        SettingsAiUsageRecorder recorder = new SettingsAiUsageRecorder(() -> settingsWith(stored), () -> { });
        List<AiProfile> notified = new ArrayList<>();
        recorder.addListener(notified::add);

        recorder.record(profile("a"), new AiTokenUsage(3, 2, 5));

        assertThat(notified).containsExactly(stored);
    }

    @Test
    void estimatesUsageWhenTheProviderReportsNone() {
        AiExecutionResult result = new AiExecutionResult("four words of answer", null);

        AiTokenUsage usage = AiUsageRecorder.usageOrEstimate(result, "system prompt", "user prompt", null);

        assertThat(usage.promptTokens()).isGreaterThan(0L);
        assertThat(usage.completionTokens()).isGreaterThan(0L);
        assertThat(AiUsageRecorder.withUsage(result, usage).usage()).isEqualTo(usage);
        AiExecutionResult reported = new AiExecutionResult("x", new AiTokenUsage(7, 3, 10));
        assertThat(AiUsageRecorder.usageOrEstimate(reported, "s", "u", null).totalTokens()).isEqualTo(10L);
        assertThat(AiUsageRecorder.withUsage(reported, usage)).isSameInstanceAs(reported);
    }
}
