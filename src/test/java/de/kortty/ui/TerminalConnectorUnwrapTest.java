package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import de.kortty.plugin.terminaleffects.TerminalEffectConnectorWrapper;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

/**
 * Per-pane state is keyed by the base connector, so the one unwrap helper must take off every
 * wrapper korTTY stacks around it, in any order: the shell-integration splitter, the colour
 * filter and the terminal effects' wrappers.
 */
class TerminalConnectorUnwrapTest {

    private static final List<UnaryOperator<TtyConnector>> WRAPPERS = List.of(
        connector -> new ShellIntegrationTtyConnector(connector, event -> { }),
        connector -> new TerminalView.TerminalColorFilteringTtyConnector(connector, () -> true, () -> { }, () -> { }),
        EffectWrapper::new);

    @Test
    void everyOrderOfEveryWrapperUnwrapsToTheBase() {
        for (List<Integer> order : permutations(List.of(0, 1, 2))) {
            for (int depth = 0; depth <= order.size(); depth++) {
                TtyConnector base = new ScriptedConnector(List.of());
                TtyConnector chain = base;
                for (int index : order.subList(0, depth)) {
                    chain = WRAPPERS.get(index).apply(chain);
                }
                assertWithMessage("wrappers %s", order.subList(0, depth))
                    .that(TerminalView.unwrapTerminalEffectConnector(chain))
                    .isSameInstanceAs(base);
            }
        }
    }

    @Test
    void aRepeatedWrapperIsTakenOffToo() {
        TtyConnector base = new ScriptedConnector(List.of());
        TtyConnector chain = WRAPPERS.get(0).apply(WRAPPERS.get(1).apply(WRAPPERS.get(0).apply(base)));

        assertThat(TerminalView.unwrapTerminalEffectConnector(chain)).isSameInstanceAs(base);
        assertThat(TerminalView.unwrapTerminalEffectConnector(null)).isNull();
    }

    @Test
    void theSplitPaneUnwrapsWithTheSameHelper() throws IOException {
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");

        assertThat(view).contains("splitPane.setConnectorUnwrapper(TerminalView::unwrapTerminalEffectConnector);");
        assertThat(view).containsMatch("static TtyConnector unwrapTerminalEffectConnector\\(TtyConnector connector\\)");
    }

    private static List<List<Integer>> permutations(List<Integer> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<Integer>> result = new ArrayList<>();
        for (Integer first : items) {
            List<Integer> rest = new ArrayList<>(items);
            rest.remove(first);
            for (List<Integer> tail : permutations(rest)) {
                List<Integer> permutation = new ArrayList<>();
                permutation.add(first);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }

    private record EffectWrapper(TtyConnector delegate) implements TerminalEffectConnectorWrapper {
        @Override
        public int read(char[] buf, int offset, int length) throws IOException {
            return delegate.read(buf, offset, length);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            delegate.write(bytes);
        }

        @Override
        public void write(String string) throws IOException {
            delegate.write(string);
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
            delegate.resize(termSize);
        }

        @Override
        public int waitFor() throws InterruptedException {
            return delegate.waitFor();
        }

        @Override
        public boolean ready() throws IOException {
            return delegate.ready();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
