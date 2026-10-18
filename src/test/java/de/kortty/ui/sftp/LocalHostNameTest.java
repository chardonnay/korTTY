package de.kortty.ui.sftp;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LocalHostNameTest {

    @Test
    void theMacLocalSuffixIsDropped() {
        assertThat(LocalHostName.displayName("Daniels-MBP.local")).isEqualTo("Daniels-MBP");
        assertThat(LocalHostName.displayName("build-box.LOCAL ")).isEqualTo("build-box");
        assertThat(LocalHostName.displayName("fedora")).isEqualTo("fedora");
        assertThat(LocalHostName.displayName("host.example.com")).isEqualTo("host.example.com");
        assertThat(LocalHostName.displayName(null)).isEmpty();
    }
}
