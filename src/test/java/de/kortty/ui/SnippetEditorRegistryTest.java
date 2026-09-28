package de.kortty.ui;

import javafx.stage.Window;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** Pure registry logic; no FX toolkit is started (windows are {@code null} or never realized). */
class SnippetEditorRegistryTest {

    /** Minimal editor double; {@code owner} stays {@code null} because a Window needs the toolkit. */
    private static final class FakeEditor implements SnippetEditorRegistry.OpenEditor {
        private final String snippetId;
        private final Window owner;
        boolean unsaved;
        final List<String> calls = new ArrayList<>();

        FakeEditor(String snippetId, Window owner) {
            this.snippetId = snippetId;
            this.owner = owner;
        }

        @Override
        public String snippetId() {
            return snippetId;
        }

        @Override
        public void reveal() {
            calls.add("reveal");
        }

        @Override
        public boolean hasUnsavedChanges() {
            return unsaved;
        }

        @Override
        public Window ownerStage() {
            return owner;
        }

        @Override
        public boolean confirmCloseFromHost() {
            calls.add("confirm");
            return true;
        }

        @Override
        public void closeWithoutPrompt() {
            calls.add("close");
        }
    }

    @BeforeMethod
    void resetRegistryBefore() {
        SnippetEditorRegistry.all().forEach(SnippetEditorRegistry::release);
    }

    @AfterMethod
    void resetRegistryAfter() {
        SnippetEditorRegistry.all().forEach(SnippetEditorRegistry::release);
    }

    @Test
    void claimBindsIdAndTracksTheEditor() {
        FakeEditor editor = new FakeEditor("s1", null);

        assertThat(SnippetEditorRegistry.claim("s1", editor)).isTrue();

        assertThat(SnippetEditorRegistry.find("s1")).hasValue(editor);
        assertThat(SnippetEditorRegistry.all()).containsExactly(editor);
    }

    @Test
    void secondEditorCannotClaimAnIdAnotherEditorHolds() {
        FakeEditor first = new FakeEditor("s1", null);
        FakeEditor second = new FakeEditor("s1", null);
        SnippetEditorRegistry.claim("s1", first);

        assertThat(SnippetEditorRegistry.claim("s1", second)).isFalse();

        assertThat(SnippetEditorRegistry.find("s1")).hasValue(first);
        assertThat(SnippetEditorRegistry.all()).containsExactly(first); // the refused editor is not tracked
    }

    @Test
    void repeatedClaimBySameEditorIsANoOp() {
        FakeEditor editor = new FakeEditor("s1", null);
        SnippetEditorRegistry.claim("s1", editor);

        assertThat(SnippetEditorRegistry.claim("s1", editor)).isTrue();

        assertThat(SnippetEditorRegistry.all()).containsExactly(editor);
    }

    @Test
    void claimingANewIdDropsTheEditorsEarlierClaim() {
        FakeEditor editor = new FakeEditor("draft-1", null);
        SnippetEditorRegistry.claim("draft-1", editor);

        assertThat(SnippetEditorRegistry.claim("saved-1", editor)).isTrue();

        assertThat(SnippetEditorRegistry.find("draft-1")).isEmpty();
        assertThat(SnippetEditorRegistry.find("saved-1")).hasValue(editor);
        FakeEditor other = new FakeEditor("draft-1", null);
        assertThat(SnippetEditorRegistry.claim("draft-1", other)).isTrue();
    }

    @Test
    void trackListsUnclaimedDraftsOnceAndReleaseForgetsEverything() {
        FakeEditor draft = new FakeEditor(null, null);
        FakeEditor claimed = new FakeEditor("s1", null);
        SnippetEditorRegistry.track(draft);
        SnippetEditorRegistry.track(draft);
        SnippetEditorRegistry.claim("s1", claimed);
        assertThat(SnippetEditorRegistry.all()).containsExactly(draft, claimed).inOrder();

        SnippetEditorRegistry.release(claimed);
        SnippetEditorRegistry.release(draft);
        SnippetEditorRegistry.release(null);
        SnippetEditorRegistry.release(new FakeEditor("unknown", null));

        assertThat(SnippetEditorRegistry.find("s1")).isEmpty();
        assertThat(SnippetEditorRegistry.all()).isEmpty();
    }

    @Test
    void findWithNullIdIsEmptyAndClaimRequiresArguments() {
        assertThat(SnippetEditorRegistry.find(null)).isEmpty();
        assertThat(SnippetEditorRegistry.find("missing")).isEmpty();
        expectThrows(NullPointerException.class, () -> SnippetEditorRegistry.claim(null, new FakeEditor("x", null)));
        expectThrows(NullPointerException.class, () -> SnippetEditorRegistry.claim("x", null));
        expectThrows(NullPointerException.class, () -> SnippetEditorRegistry.track(null));
    }

    @Test
    void standaloneOwnedByMatchesTheOwnerStageByIdentity() {
        FakeEditor ownerless = new FakeEditor("s1", null);
        SnippetEditorRegistry.claim("s1", ownerless);

        // No toolkit: the only Window we can compare against is null, which is exactly the
        // owner-less swarm editor case the quit guard has to cover.
        assertThat(SnippetEditorRegistry.standaloneOwnedBy(null)).containsExactly(ownerless);
    }

    @Test
    void allReturnsASnapshot() {
        FakeEditor editor = new FakeEditor("s1", null);
        SnippetEditorRegistry.claim("s1", editor);
        List<SnippetEditorRegistry.OpenEditor> snapshot = SnippetEditorRegistry.all();

        SnippetEditorRegistry.release(editor);

        assertThat(snapshot).containsExactly(editor);
        assertThat(SnippetEditorRegistry.all()).isEmpty();
    }
}
