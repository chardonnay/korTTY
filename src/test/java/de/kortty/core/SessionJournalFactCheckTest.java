package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalFactCheckTest {

    /** What the real 2026-10-03 journal held: the log shows www.heise.de, never checkip. */
    private static final String EVIDENCE = """
        sudo dnf install links
        links www.heise.de
        heise online - IT-News, Nachrichten und Hintergründe | heise online https://www.heise.de/
        daniel@fedora:~/Dokumente$ ./server_auslastung.pl
        """;

    @Test
    void dropsTheBulletWithTheInventedUrlAndKeepsTheRest() {
        SessionJournalFactCheck.Result result = SessionJournalFactCheck.dropUnverified("""
            - `links` wurde per `sudo dnf install` installiert.
            - Mit `links` wurde die Webseite `https://checkip.dyndns.org/` aufgerufen und die externe IP-Adresse ausgelesen.
            - Das Skript `./server_auslastung.pl` wurde ausgeführt.""", EVIDENCE);

        assertThat(result.dropped()).isEqualTo(1);
        assertThat(result.text()).doesNotContain("checkip");
        assertThat(result.text()).contains("server_auslastung.pl");
        assertThat(result.text()).contains("sudo dnf install");
    }

    @Test
    void keepsTheRealSiteHoweverItIsWritten() {
        String text = "Mit `links` wurde https://www.heise.de/ aufgerufen. Danach wurde heise.de erneut geöffnet.";
        assertThat(SessionJournalFactCheck.dropUnverified(text, EVIDENCE).dropped()).isEqualTo(0);
    }

    @Test
    void dropsOnlyTheInventedSentenceOfAProseSummary() {
        SessionJournalFactCheck.Result result = SessionJournalFactCheck.dropUnverified(
            "Zunächst wurde `links` installiert. Dann wurde example.org geprüft. Die Version ist 2.20.2, z.B. aarch64.",
            EVIDENCE);
        assertThat(result.text()).isEqualTo("Zunächst wurde `links` installiert. Die Version ist 2.20.2, z.B. aarch64.");
        assertThat(result.dropped()).isEqualTo(1);
    }

    @Test
    void cutsAtASentenceAndMarksTheCut() {
        String text = "Erster Satz ist hier. Zweiter Satz ist deutlich laenger als der Rest des Textes.";
        assertThat(SessionJournalSummarizer.capAtSentence(text, 40)).isEqualTo("Erster Satz ist hier. [...]");
        assertThat(SessionJournalSummarizer.capAtSentence(text, 500)).isEqualTo(text);
    }
}
