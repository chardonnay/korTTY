package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiManagerApiKeyPromptTest {

    @Test
    void storedKeyIsAnnouncedInTheEmptyField() {
        String key = AiManagerDialog.apiKeyPromptKey("encrypted-blob", false);
        assertThat(key).isEqualTo("ai.manager.profile.apiKey.stored");
        assertThat(I18n.get(key)).isNotEqualTo(key);
    }

    @Test
    void noHintWithoutStoredKeyOrWhenClearIsRequested() {
        assertThat(AiManagerDialog.apiKeyPromptKey(null, false)).isNull();
        assertThat(AiManagerDialog.apiKeyPromptKey(" ", false)).isNull();
        assertThat(AiManagerDialog.apiKeyPromptKey("encrypted-blob", true)).isNull();
    }

    @Test
    void storedSecretStaysInEffectOnlyWhileNotCleared() {
        assertThat(AiManagerDialog.storedSecretKept("encrypted-blob", false)).isTrue();
        assertThat(AiManagerDialog.storedSecretKept("encrypted-blob", true)).isFalse();
        assertThat(AiManagerDialog.storedSecretKept(null, false)).isFalse();
        assertThat(AiManagerDialog.storedSecretKept("", false)).isFalse();
    }
}
