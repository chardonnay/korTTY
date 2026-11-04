package de.kortty.core;

import de.kortty.core.SnippetPlaceholderScanner.Placeholder;
import de.kortty.core.SnippetPlaceholderScanner.Status;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.google.common.truth.Truth.assertThat;

class SnippetPlaceholderScannerTest {

    private static final Map<String, String> DECLARED = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static {
        DECLARED.put("server", "web01");
        DECLARED.put("ticket", "");
    }

    private static final SnippetPlaceholderResolver.Declarations DECLARATIONS = DECLARED::containsKey;

    @Test
    void groupsEveryPlaceholderTheWayAUseTreatsIt() {
        String content = """
            echo "${date} on ${hostname}"
            ssh ${server} 'deploy ${ticket}'
            cd ${target}${cursor}
            echo ${HOME} ${1:-default} ${#items[@]} $${escaped} ${SERVER}
            """;

        List<Placeholder> found = SnippetPlaceholderScanner.scan(content, DECLARATIONS, DECLARED::get);

        assertThat(found.stream().map(p -> p.name() + "=" + p.status() + "x" + p.occurrences()).toList())
            .containsExactly("date=BUILT_INx1", "hostname=BUILT_INx1", "server=STOREDx2", "ticket=ASKSx1",
                "target=UNDECLAREDx1", "cursor=BUILT_INx1").inOrder();
        Placeholder server = found.get(2);
        assertThat(content.substring(server.start(), server.end())).isEqualTo("${server}");
        assertThat(server.line()).isEqualTo(2);
    }

    @Test
    void undeclaredGroupMatchesWhatScheduledRunsRefuse() {
        String content = "${a} ${b_2} ${HOME} ${PATH} ${_} ${1} ${x:-y} ${server} ${date} $${c} ${a}";

        List<String> undeclared = SnippetPlaceholderScanner.scan(content, DECLARATIONS, DECLARED::get).stream()
            .filter(p -> p.status() == Status.UNDECLARED)
            .map(Placeholder::name)
            .toList();

        assertThat(undeclared).isEqualTo(SnippetPlaceholderResolver.undeclaredSimpleNames(content, DECLARATIONS));
    }

    @Test
    void emptyOrNullContentHasNoPlaceholders() {
        assertThat(SnippetPlaceholderScanner.scan(null, DECLARATIONS, DECLARED::get)).isEmpty();
        assertThat(SnippetPlaceholderScanner.scan("", null, null)).isEmpty();
        assertThat(SnippetPlaceholderScanner.scan("${x}", null, null).get(0).status()).isEqualTo(Status.UNDECLARED);
    }
}
