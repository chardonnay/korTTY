package de.kortty.core;

import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalHeaderSupportTest {

    private static SessionJournalMeta meta(String title, String user, String host, int port, String name) {
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setTitle(title);
        meta.setUsername(user);
        meta.setHost(host);
        meta.setPort(port);
        meta.setConnectionName(name);
        return meta;
    }

    @Test
    void showsEndpointAndNameWhenTheTitleSaysNeither() {
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("Nginx maintenance", "daniel", "10.211.55.5", 22, "Production")))
            .isEqualTo("daniel@10.211.55.5:22 · Production");
    }

    @Test
    void dropsTheNameWhenTheTitleAlreadyCarriesIt() {
        // The default title is "<connection> — <date>", so the name is always in the title.
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("Production — 2026-08-03 10:09", "daniel", "10.211.55.5", 22, "Production")))
            .isEqualTo("daniel@10.211.55.5:22");
    }

    @Test
    void dropsEverythingWhenTheConnectionIsNamedAfterItsEndpoint() {
        // The reported case: title, endpoint and connection name were the same connection.
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("daniel@10.211.55.5 — 2026-08-03 10:09", "daniel", "10.211.55.5", 22,
                "daniel@10.211.55.5")))
            .isEmpty();
    }

    @Test
    void dropsNamesThatOnlyRestateTheEndpoint() {
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("Maintenance", "daniel", "10.211.55.5", 22, "10.211.55.5")))
            .isEqualTo("daniel@10.211.55.5:22");
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("Maintenance", "daniel", "10.211.55.5", 22, "daniel@10.211.55.5:22")))
            .isEqualTo("daniel@10.211.55.5:22");
    }

    @Test
    void comparesCaseInsensitivelyAndIgnoresSurroundingSpace() {
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("Daniel@Web01 — 2026-08-03", " daniel ", " web01 ", 22, "  DANIEL@WEB01  ")))
            .isEmpty();
    }

    @Test
    void keepsTheEndpointWhenOnlyTheHostAppearsInTheTitle() {
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta("web01 outage", "daniel", "web01", 2222, null)))
            .isEqualTo("daniel@web01:2222");
    }

    @Test
    void toleratesMissingFields() {
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(null)).isEmpty();
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta(null, null, null, 0, null))).isEmpty();
        assertThat(SessionJournalHeaderSupport.connectionSubtitle(
            meta(null, null, "web01", 22, null))).isEqualTo("web01:22");
    }
}
