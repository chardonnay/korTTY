package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class MonospaceFontFamiliesTest {

    @Test
    void sortsInstalledMonospaceFamiliesToTheFrontKeepingTheirPreferredOrder() {
        List<String> result = MonospaceFontFamilies.monospaceFirst(
            List.of("Arial", "Menlo", "Zapfino", "Monospaced", "Consolas"));

        assertThat(result).containsExactly(
            "Monospaced", "Consolas", "Menlo", "Arial", "Zapfino").inOrder();
    }

    @Test
    void keepsEveryInstalledFamilyEvenWhenNoPreferredFontIsPresent() {
        List<String> installed = List.of("Arial", "Zapfino");

        assertThat(MonospaceFontFamilies.monospaceFirst(installed))
            .containsExactlyElementsIn(installed).inOrder();
    }

    @Test
    void toleratesAnEmptyOrMissingFamilyList() {
        assertThat(MonospaceFontFamilies.monospaceFirst(List.of())).isEmpty();
        assertThat(MonospaceFontFamilies.monospaceFirst(null)).isEmpty();
    }
}
