package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenLimitUnit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiTokenUsageManagerTest {

    @Test
    void refreshUsageResetsExpiredCycleAndComputesWarningLevel() {
        AiProfile profile = new AiProfile();
        profile.setTokenLimitAmount(100L);
        profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        profile.setTokenWarningYellowPercent(50);
        profile.setTokenWarningRedPercent(80);
        profile.setTokenResetPeriodDays(30);
        profile.setTokenResetAnchorDate("2026-01-01");
        profile.setTokenUsageCycleStartDate("2026-01-01");
        profile.setUsedTotalTokens(81_000L);

        AiTokenUsageSnapshot resetSnapshot = AiTokenUsageManager.refreshUsage(profile, LocalDate.of(2026, 2, 5));

        assertEquals(0L, resetSnapshot.usedTotalTokens());
        assertEquals(AiTokenWarningLevel.NONE, resetSnapshot.warningLevel());

        profile.setUsedTotalTokens(82_000L);
        AiTokenUsageSnapshot warningSnapshot = AiTokenUsageManager.refreshUsage(profile, LocalDate.of(2026, 2, 10));

        assertEquals(AiTokenWarningLevel.RED, warningSnapshot.warningLevel());
        assertEquals(18_000L, warningSnapshot.remainingTokens());
    }
}
