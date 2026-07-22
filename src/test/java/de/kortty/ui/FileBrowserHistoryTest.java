package de.kortty.ui;

import org.testng.annotations.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.google.common.truth.Truth.assertThat;

class FileBrowserHistoryTest {

    private static final Path A = Paths.get("/a");
    private static final Path B = Paths.get("/b");
    private static final Path C = Paths.get("/c");

    @Test
    void navigateRecordsPreviousAndClearsForward() {
        FileBrowserHistory history = new FileBrowserHistory();
        history.navigate(A);
        assertThat(history.current()).isEqualTo(A);
        assertThat(history.canGoBack()).isFalse();
        assertThat(history.canGoForward()).isFalse();

        history.navigate(B);
        assertThat(history.current()).isEqualTo(B);
        assertThat(history.canGoBack()).isTrue();
        assertThat(history.canGoForward()).isFalse();
    }

    @Test
    void backThenForwardReturnsToStart() {
        FileBrowserHistory history = new FileBrowserHistory();
        history.navigate(A);
        history.navigate(B);

        assertThat(history.back()).isEqualTo(A);
        assertThat(history.canGoBack()).isFalse();
        assertThat(history.canGoForward()).isTrue();

        assertThat(history.forward()).isEqualTo(B);
        assertThat(history.canGoForward()).isFalse();
        assertThat(history.canGoBack()).isTrue();
    }

    @Test
    void navigatingToSamePathIsNoOp() {
        FileBrowserHistory history = new FileBrowserHistory();
        history.navigate(A);
        history.navigate(A);
        assertThat(history.current()).isEqualTo(A);
        assertThat(history.canGoBack()).isFalse();
    }

    @Test
    void newNavigationAfterBackDropsForwardStack() {
        FileBrowserHistory history = new FileBrowserHistory();
        history.navigate(A);
        history.navigate(B);
        history.back(); // now at A, forward = [B]
        assertThat(history.canGoForward()).isTrue();

        history.navigate(C);
        assertThat(history.current()).isEqualTo(C);
        assertThat(history.canGoForward()).isFalse();
        assertThat(history.back()).isEqualTo(A);
    }

    @Test
    void backAndForwardAreNoOpsWhenStacksEmpty() {
        FileBrowserHistory history = new FileBrowserHistory();
        assertThat(history.back()).isNull();
        assertThat(history.forward()).isNull();

        history.navigate(A);
        assertThat(history.back()).isEqualTo(A);
        assertThat(history.forward()).isEqualTo(A);
    }
}
