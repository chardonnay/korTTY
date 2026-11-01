package de.kortty.memory;

import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The settings of a rule check hold copies of the skills, so an edit on the FX thread cannot reach the check. */
class RuleDetectorSettingsCopyTest {

    @Test
    void aLaterEditOfTheLiveSkillDoesNotChangeTheSettingsOfACheck() {
        AiSkill live = new AiSkill();
        live.setId("deploy");
        live.setName("Deploy");
        RuleDetector.Settings settings = new RuleDetector.Settings(true, true, true, null, List.of(live), null);

        live.setName("Renamed on the FX thread");

        assertThat(settings.skills()).hasSize(1);
        assertThat(settings.skills().get(0).getName()).isEqualTo("Deploy");
        assertThat(settings.skills().get(0)).isNotSameInstanceAs(live);
    }
}
