package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class DeepLTranslationServiceTest {

    @Test
    void rejectsAnEmptyKeyWithoutCallingTheApi() {
        assertThat(new DeepLTranslationService("  ").translateBatch(List.of("Hello"), "en", "de"))
            .isNull();
    }

    @Test
    void keepsTheRegionOnlyForVariantsDeepLDistinguishes() {
        assertThat(DeepLTranslationService.toDeepLLang("pt_BR")).isEqualTo("PT-BR");
        assertThat(DeepLTranslationService.toDeepLLang("en-gb")).isEqualTo("EN-GB");
        // DeepL has no DE-AT target, so the region has to be dropped rather than sent and refused.
        assertThat(DeepLTranslationService.toDeepLLang("de_AT")).isEqualTo("DE");
        assertThat(DeepLTranslationService.toDeepLLang("fr")).isEqualTo("FR");
        assertThat(DeepLTranslationService.toDeepLLang("")).isNull();
        assertThat(DeepLTranslationService.toDeepLLang(null)).isNull();
    }
}
