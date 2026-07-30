package de.kortty.core;

import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.time.Duration;

import static com.google.common.truth.Truth.assertThat;

class AiRequestTimeoutSupportTest {

    @Test
    void resolvesToNoTimeoutWhenNothingIsConfigured() {
        assertThat(AiRequestTimeoutSupport.resolve(profileWithTimeout(null), 0)).isNull();
        assertThat(AiRequestTimeoutSupport.resolve(null, 0)).isNull();
    }

    @Test
    void usesTheGlobalTimeoutWhenTheProfileHasNoOverride() {
        assertThat(AiRequestTimeoutSupport.resolve(profileWithTimeout(null), 15))
            .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void profileOverrideWinsOverTheGlobalTimeout() {
        assertThat(AiRequestTimeoutSupport.resolve(profileWithTimeout(45), 15))
            .isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void profileOverrideOfZeroDisablesTheGlobalTimeout() {
        // The whole point of the per-profile override: a profile that runs long analyses must be
        // able to opt out of a global limit rather than only tighten it.
        assertThat(AiRequestTimeoutSupport.resolve(profileWithTimeout(0), 15)).isNull();
    }

    @Test
    void negativeProfileValuesFallBackToTheGlobalTimeout() {
        AiProfile profile = new AiProfile();
        profile.setRequestTimeoutMinutes(-5);

        assertThat(profile.getRequestTimeoutMinutes()).isNull();
        assertThat(AiRequestTimeoutSupport.resolve(profile, 15)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void copiedProfilesKeepTheirOverride() {
        AiProfile copy = new AiProfile(profileWithTimeout(30));

        assertThat(copy.getRequestTimeoutMinutes()).isEqualTo(30);
    }

    private static AiProfile profileWithTimeout(Integer minutes) {
        AiProfile profile = new AiProfile();
        profile.setRequestTimeoutMinutes(minutes);
        return profile;
    }
}
