package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class PasswordPromptDetectorTest {

    @Test
    void broadCheckMatchesContainedKeywords() {
        assertThat(PasswordPromptDetector.isPasswordPrompt("Password:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPrompt("Enter your PASSCODE now")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPrompt("Vault token")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPrompt("hello world")).isFalse();
        assertThat(PasswordPromptDetector.isPasswordPrompt(null)).isFalse();
    }

    @Test
    void lineCheckMatchesTypicalPrompts() {
        assertThat(PasswordPromptDetector.isPasswordPromptLine("[sudo] password for daniel:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("daniel@192.168.1.50's password: ")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Enter passphrase for key '/home/d/.ssh/id_ed25519':")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("PIN:")).isTrue();
    }

    @Test
    void lineCheckMatchesLocalizedPrompts() {
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Passwort:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Kennwort:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Contraseña:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Mot de passe :")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Wachtwoord:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Lozinka:")).isTrue();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("Senha:")).isTrue();
    }

    @Test
    void lineCheckIsPromptShaped() {
        // Ordinary output mentioning passwords must not trip the suppression.
        assertThat(PasswordPromptDetector.isPasswordPromptLine("cat passwords.txt")).isFalse();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("-rw-r--r-- 1 root root 120 passwords.txt")).isFalse();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("password was rejected, try again later")).isFalse();
        // Long colon-terminated output lines are not prompts.
        assertThat(PasswordPromptDetector.isPasswordPromptLine(
            "the password policy requires the following, which is described in exhaustive detail below "
                + "and continues for quite a while to exceed the prompt length limit set here:")).isFalse();
        // Short colon-terminated lines mentioning a password ARE suppressed by design (err toward
        // suppression: a false positive costs one redacted input line, a false negative a secret).
        assertThat(PasswordPromptDetector.isPasswordPromptLine("grep results for password:")).isTrue();
    }

    @Test
    void lineCheckHandlesNullAndBlank() {
        assertThat(PasswordPromptDetector.isPasswordPromptLine(null)).isFalse();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("   ")).isFalse();
        assertThat(PasswordPromptDetector.isPasswordPromptLine("password")).isFalse(); // no colon
    }
}
