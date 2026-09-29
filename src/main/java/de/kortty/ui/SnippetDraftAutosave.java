package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SnippetDiagramSupport;
import de.kortty.core.SnippetDraftStore;
import de.kortty.core.SnippetDraftStore.SnippetDraft;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The draft autosave of one snippet editor: while the form differs from the saved snippet, its
 * state is written to the {@link SnippetDraftStore} two seconds after the last change; a save or
 * an explicit discard removes it again. When the editor opens and an older draft exists, an inline
 * banner offers to restore or discard it — a draft is never applied by itself, and while the
 * banner waits for an answer nothing overwrites the older draft.
 *
 * <p>Everything here runs on the JavaFX thread; the store does its I/O on its own thread.</p>
 */
final class SnippetDraftAutosave {

    static final String BANNER_ID = "snippet-draft-banner";
    static final String RESTORE_ID = "snippet-draft-restore";
    static final String DISCARD_ID = "snippet-draft-discard";
    static final Duration DEBOUNCE = Duration.seconds(2);

    private static final Logger logger = LoggerFactory.getLogger(SnippetDraftAutosave.class);
    /** Ids of the editors that are open right now (their drafts are not orphans). */
    private static final Set<String> LIVE_IDS = new HashSet<>();

    /** The editor seen from the autosave. */
    interface Form {
        String snippetId();

        /** The snippet was never saved (its id is the editor's draft id). */
        boolean isNewSnippet();

        boolean hasUnsavedChanges();

        /** The content as last saved, or {@code null} for a never-saved snippet. */
        String savedContent();

        /** The current form as a draft stamped {@code now}. */
        SnippetDraft capture(long now);

        /** Fills the form from {@code draft}; the editor then has unsaved changes. */
        void restore(SnippetDraft draft);

        void showBanner(Region banner);

        void hideBanner(Region banner);
    }

    private final Form form;
    private final SnippetDraftStore store;
    private final PauseTransition debounce = new PauseTransition(DEBOUNCE);
    private final String liveId;
    /** This editor wrote (or took over) the stored draft, so it may remove it. */
    private boolean owned;
    /** An older draft waits for Restore / Discard; nothing is written meanwhile. */
    private SnippetDraft offered;
    private Region banner;
    private boolean disposed;

    SnippetDraftAutosave(Form form, SnippetDraftStore store) {
        this.form = Objects.requireNonNull(form, "form");
        this.store = Objects.requireNonNull(store, "store");
        this.liveId = form.snippetId();
        if (liveId != null) {
            LIVE_IDS.add(liveId);
        }
        debounce.setOnFinished(event -> writeNow());
    }

    /** Ids of the snippets (and drafts) open in an editor right now; FX thread. */
    static Set<String> liveIds() {
        return Collections.unmodifiableSet(new HashSet<>(LIVE_IDS));
    }

    /** Looks for a draft left behind (a crash, a kill) and offers it; never blocks. */
    void checkForDraft() {
        String id = form.snippetId();
        if (id == null || id.isBlank()) {
            return;
        }
        store.load(id).whenComplete((draft, error) -> runOnFx(() -> {
            if (error != null) {
                logger.debug("Could not look for a snippet draft of {}", id, error);
                return;
            }
            if (draft != null && draft.isPresent()) {
                offer(draft.get());
            }
        }));
    }

    /** Called after every form change. */
    void formChanged() {
        if (disposed || offered != null) {
            return;
        }
        if (form.hasUnsavedChanges()) {
            debounce.playFromStart();
        } else {
            debounce.stop();
            if (owned) {
                owned = false;
                store.delete(form.snippetId());
            }
        }
    }

    /** The editor saved: the draft is obsolete. A draft still on offer stays (the user did not answer). */
    void saved() {
        debounce.stop();
        if (owned) {
            owned = false;
            store.delete(form.snippetId());
        }
    }

    /** The user discarded the unsaved changes (close → Discard). */
    void discarded() {
        saved();
    }

    /**
     * The editor closes. Every regular close either saved or discarded (the close guards ask), so
     * a draft this editor wrote goes; an offered draft nobody answered stays for the next open.
     */
    void dispose() {
        if (disposed) {
            return;
        }
        debounce.stop();
        if (owned) {
            owned = false;
            store.delete(form.snippetId());
        }
        disposed = true;
        if (liveId != null) {
            LIVE_IDS.remove(liveId);
        }
    }

    /** Test seam: writes a pending draft now instead of after the debounce. */
    void flushForTesting() {
        if (debounce.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            debounce.stop();
            writeNow();
        }
    }

    boolean isOffering() {
        return offered != null;
    }

    /** The hash of the content of the draft on offer, or {@code null} when none waits for an answer. */
    String offeredContentSha256() {
        SnippetDraft draft = offered;
        return draft != null ? de.kortty.core.SnippetDiagramSupport.contentHash(draft.content()) : null;
    }

    Region bannerForTesting() {
        return banner;
    }

    // ---- banner ----

    /** Shows {@code draft} for restoring (also used for a draft of a never-saved snippet). */
    void offer(SnippetDraft draft) {
        if (disposed || draft == null) {
            return;
        }
        SnippetDraft current = form.capture(draft.savedAt());
        if (current != null && draft.sameFormAs(current)) {
            // Nothing to restore (e.g. the save landed but the delete did not before a kill).
            store.delete(draft.snippetId());
            return;
        }
        offered = draft;
        debounce.stop();
        Label label = new Label(bannerText(draft, form.savedContent()));
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(label, Priority.ALWAYS);
        Button restore = new Button(I18n.get("snippets.draft.restore"));
        restore.setId(RESTORE_ID);
        restore.setOnAction(event -> restoreOffered());
        Button discard = new Button(I18n.get("snippets.draft.discard"));
        discard.setId(DISCARD_ID);
        discard.setOnAction(event -> discardOffered());
        HBox row = new HBox(8, label, restore, discard);
        row.setId(BANNER_ID);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-color: rgba(245,158,11,0.14); -fx-border-color: rgba(245,158,11,0.55);"
            + " -fx-border-radius: 6; -fx-background-radius: 6;");
        hideBanner();
        banner = row;
        form.showBanner(row);
    }

    void restoreOffered() {
        SnippetDraft draft = offered;
        if (draft == null || disposed) {
            return;
        }
        offered = null;
        hideBanner();
        form.restore(draft);
        // The editor now holds the draft's state and owns the file; keep it current.
        owned = true;
        formChanged();
    }

    void discardOffered() {
        SnippetDraft draft = offered;
        if (draft == null) {
            return;
        }
        offered = null;
        hideBanner();
        store.delete(draft.snippetId());
        formChanged();
    }

    private void hideBanner() {
        if (banner != null) {
            form.hideBanner(banner);
            banner = null;
        }
    }

    private void writeNow() {
        if (disposed || offered != null || !form.hasUnsavedChanges()) {
            return;
        }
        SnippetDraft draft = form.capture(System.currentTimeMillis());
        if (draft == null) {
            return;
        }
        store.save(draft);
        owned = true;
    }

    /** "Unsaved changes from <date> …", with a hint when the snippet was saved differently since. */
    static String bannerText(SnippetDraft draft, String savedContent) {
        String when = formatTime(draft.savedAt());
        String text = I18n.get("snippets.draft.banner", when);
        if (!draft.newSnippet() && !draft.baseContentSha256().isBlank() && savedContent != null
                && !draft.baseContentSha256().equals(SnippetDiagramSupport.contentHash(savedContent))) {
            text += " " + I18n.get("snippets.draft.banner.changedSince");
        }
        return text;
    }

    static String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "—";
        }
        Locale locale;
        try {
            locale = LanguageManager.getInstance().getCurrentLocale();
        } catch (RuntimeException e) {
            locale = null;
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale != null ? locale : Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            try {
                Platform.runLater(action);
            } catch (IllegalStateException e) {
                action.run();
            }
        }
    }
}
