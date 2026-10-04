package de.kortty.ui.sftp;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.sftp.transfer.ConflictAction;
import de.kortty.core.sftp.transfer.PartRetention;
import de.kortty.core.sftp.transfer.ResumeIndex;
import de.kortty.core.sftp.transfer.TransferSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SftpConflictDefault;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/** How an SFTP tab's queue settings follow Settings › SFTP Manager and the {@code [rule.sftp]} policy. */
class SftpTransferSettingsFromTest {

    private static final ResumeIndex INDEX = new ResumeIndex(Path.of("unused", ResumeIndex.FILE_NAME));

    private static EffectivePolicy policy(Integer cap, SftpConflictDefault conflict) {
        PolicyRule rule = PolicyRule.builder().sftp(new PolicyRule.SftpRule(cap, conflict)).build();
        return EffectivePolicy.resolve(new PolicyFile(1, null, Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of()), new PolicyIdentity() {
                @Override
                public String userName() {
                    return "u";
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
    }

    @Test
    void theDefaultsAreThreeChannelsAskAndResume() {
        TransferSettings settings = SftpTransferQueueHost.settingsFrom(new GlobalSettings(),
            EffectivePolicy.unrestricted(), "conn-1", INDEX);
        assertThat(settings.parallelTransfers()).isEqualTo(3);
        assertThat(settings.conflictDefault()).isNull();
        assertThat(settings.resumeEnabled()).isTrue();
        assertThat(settings.resumeScope()).isEqualTo("conn-1");
        assertThat(settings.partRetention()).isEqualTo(PartRetention.KEEP_ON_FAILURE);
    }

    @Test
    void theUsersChoicesAreUsed() {
        GlobalSettings global = new GlobalSettings();
        global.setSftpParallelTransfers(6);
        global.setSftpConflictDefault(SftpConflictDefault.SKIP);
        global.setSftpKeepPartialOnCancel(true);
        TransferSettings settings = SftpTransferQueueHost.settingsFrom(global, null, "c", INDEX);
        assertThat(settings.parallelTransfers()).isEqualTo(6);
        assertThat(settings.conflictDefault()).isEqualTo(ConflictAction.SKIP);
        assertThat(settings.partRetention()).isEqualTo(PartRetention.KEEP);

        global.setSftpResumePartialTransfers(false);
        TransferSettings noResume = SftpTransferQueueHost.settingsFrom(global, null, "c", INDEX);
        assertThat(noResume.resumeEnabled()).isFalse();
        assertThat(noResume.partRetention()).isEqualTo(PartRetention.DISCARD);
    }

    @Test
    void thePolicyCapsAndPinsEvenWhenTheStoredValuesDiverge() {
        GlobalSettings global = new GlobalSettings();
        global.setSftpParallelTransfers(8);
        global.setSftpConflictDefault(SftpConflictDefault.OVERWRITE);
        TransferSettings settings = SftpTransferQueueHost.settingsFrom(global,
            policy(2, SftpConflictDefault.ASK), "c", INDEX);
        assertThat(settings.parallelTransfers()).isEqualTo(2);
        assertThat(settings.conflictDefault()).isNull();
    }

    @Test
    void askMapsToAPromptAndTheOthersToTheirAction() {
        assertThat(SftpTransferQueueHost.conflictAction(SftpConflictDefault.ASK)).isNull();
        assertThat(SftpTransferQueueHost.conflictAction(null)).isNull();
        assertThat(SftpTransferQueueHost.conflictAction(SftpConflictDefault.OVERWRITE))
            .isEqualTo(ConflictAction.OVERWRITE);
    }
}
