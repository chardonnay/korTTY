package de.kortty.model;

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Guard for {@link ServerConnection#copyForAuth}: it copies every persisted field by hand and is
 * the funnel for all connections.xml saves (XMLConnectionRepository.prepareForPersistence), so a
 * field that is missing there is silently reset on the next save. This test populates every
 * persisted field with a value that differs from a fresh instance's default and asserts the copy
 * carries each one — adding a field without extending copyForAuth fails here immediately.
 */
public class ServerConnectionCopyForAuthTest {

    /**
     * Fields copyForAuth deliberately copies by value instead of sharing the reference; they are
     * compared field-by-field instead of via {@link Objects#equals}.
     */
    private static final Set<String> DEEP_COPIED_FIELDS = Set.of("logConfig");

    @Test
    void copyForAuthCarriesEveryPersistedField() throws Exception {
        ServerConnection source = new ServerConnection();
        ServerConnectionFieldFixture.populateAllDistinct(source, DEEP_COPIED_FIELDS);

        ServerConnection copy = ServerConnection.copyForAuth(source);

        List<String> dropped = new ArrayList<>();
        for (Field field : ServerConnectionFieldFixture.persistedFields()) {
            Object expected = field.get(source);
            Object actual = field.get(copy);
            boolean carried = DEEP_COPIED_FIELDS.contains(field.getName())
                    ? ServerConnectionFieldFixture.equalByFields(expected, actual)
                    : Objects.equals(expected, actual);
            if (!carried) {
                dropped.add(field.getName() + " (expected " + expected + ", but copy has " + actual + ")");
            }
        }

        assertWithMessage(
                "ServerConnection.copyForAuth drops persisted fields; every field JAXB persists"
                        + " must be copied there, or the next save of connections.xml silently"
                        + " resets it")
                .that(dropped)
                .isEmpty();
    }

    /**
     * The tab color is a per-connection override: copyForAuth is the save funnel and the copy a tab
     * of a teamwork or Quick Connect session holds, so a color must survive it and no color must
     * stay no color.
     */
    @Test
    void copyForAuthKeepsTheTabColorAndItsAbsence() {
        ServerConnection colored = new ServerConnection("prod", "db.example.com", 22, "root");
        colored.setTabColor("#D32F2F");
        ServerConnection plain = new ServerConnection("dev", "dev.example.com", 22, "root");

        assertThat(ServerConnection.copyForAuth(colored).getTabColor()).isEqualTo("#D32F2F");
        assertThat(ServerConnection.copyForAuth(plain).getTabColor()).isNull();
    }
}
