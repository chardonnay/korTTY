package de.kortty.ui;

import de.kortty.ui.TabRestoreTriage.Classification;
import de.kortty.ui.TabRestoreTriage.Outcome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The tabs of an opened project that did not open right away, as one window's restore bar lists them:
 * those waiting for a password or a new temporary SSH key, those waiting for the vault, and those the
 * server policy blocks or whose connection or file is gone. The list keeps the saved order, so a tab
 * that goes back in after a cancelled question keeps its place. It decides what the bar offers and
 * says; the window opens the tabs. Toolkit-free; FX thread only in the application.
 *
 * @param <T> what the window needs to open a tab later
 */
final class RestoreAttention<T> {

    static final String SUMMARY_KEY = "session.restore.attention";
    static final String ITEM_KEY = "session.restore.item";
    static final String CONNECT_KEY = "session.restore.connect";
    static final String UNLOCK_KEY = "session.restore.unlock";
    static final String DETAILS_KEY = "session.restore.details";
    static final String DISMISS_KEY = "session.restore.dismiss";
    static final String TOOLTIP_KEY = "session.restore.tooltip";
    static final String COUNT_CREDENTIALS_KEY = "session.restore.count.credentials";
    static final String COUNT_UNLOCK_KEY = "session.restore.count.unlock";
    static final String COUNT_BLOCKED_KEY = "session.restore.count.blocked";
    static final String COUNT_MISSING_KEY = "session.restore.count.missing";

    /** Every text of the restore bar, for the bundle coverage test. */
    static final List<String> KEYS = List.of(
        SUMMARY_KEY, ITEM_KEY, CONNECT_KEY, UNLOCK_KEY, DETAILS_KEY, DISMISS_KEY, TOOLTIP_KEY,
        COUNT_CREDENTIALS_KEY, COUNT_UNLOCK_KEY, COUNT_BLOCKED_KEY, COUNT_MISSING_KEY,
        TabRestoreTriage.Reason.PASSWORD.i18nKey(),
        TabRestoreTriage.Reason.TEMPORARY_KEY.i18nKey(),
        TabRestoreTriage.Reason.VAULT_LOCKED.i18nKey(),
        TabRestoreTriage.Reason.BLOCKED.i18nKey(),
        TabRestoreTriage.Reason.CONNECTION_MISSING.i18nKey(),
        TabRestoreTriage.Reason.FILE_MISSING.i18nKey(),
        "session.restore.tab.terminal",
        "session.restore.tab.sftp",
        "session.restore.tab.sftpUnnamed");

    /**
     * One waiting tab.
     *
     * @param tab          what the window needs to open it
     * @param order        its position in the saved window; the list is sorted by it
     * @param label        its name in the bar (see {@link TabRestoreTriage#label})
     * @param connectionId the id of the connection it opens over; tabs with the same id share one
     *                     question on Connect…; {@code null} for a local file or a missing connection
     */
    record Item<T>(T tab, int order, String label, @Nullable String connectionId, Outcome outcome) {

        Item {
            Objects.requireNonNull(tab, "tab");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(outcome, "outcome");
            if (outcome.isReady()) {
                throw new IllegalArgumentException("A tab that is ready opens; it does not wait");
            }
        }

        Classification classification() {
            return outcome.classification();
        }

        /** The same tab with a newer decision, for instance after a cancelled question or a policy change. */
        Item<T> with(Outcome newer) {
            return new Item<>(tab, order, label, connectionId, newer);
        }
    }

    private final List<Item<T>> items = new ArrayList<>();

    /** Adds a waiting tab at its saved place; the same item twice is kept once. */
    void add(Item<T> item) {
        Objects.requireNonNull(item, "item");
        if (containsSame(item)) {
            return;
        }
        int at = 0;
        while (at < items.size() && items.get(at).order() <= item.order()) {
            at++;
        }
        items.add(at, item);
    }

    /**
     * Takes {@code item} out, because it is about to be opened or asked about.
     *
     * @return whether it was still waiting; {@code false} when something else took it meanwhile
     */
    boolean remove(Item<T> item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == item) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Forgets every waiting tab: Dismiss, or another project opened in the window. */
    void clear() {
        items.clear();
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** The waiting tabs in their saved order. */
    List<Item<T>> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** The waiting tabs of one class, in their saved order. */
    List<Item<T>> waiting(Classification classification) {
        List<Item<T>> matching = new ArrayList<>();
        for (Item<T> item : items) {
            if (item.classification() == classification) {
                matching.add(item);
            }
        }
        return matching;
    }

    /** The first tab Connect… asks about: the first one waiting for a password, a key or the vault. */
    Optional<Item<T>> nextToConnect() {
        for (Item<T> item : items) {
            if (item.classification().waitsForUser()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    /**
     * The other tabs that wait for the same connection as {@code item}: once the user has signed in to
     * it, they open with the same password or key instead of asking again.
     */
    List<Item<T>> sameConnection(Item<T> item) {
        List<Item<T>> matching = new ArrayList<>();
        if (item.connectionId() == null) {
            return matching;
        }
        for (Item<T> other : items) {
            if (other != item && other.classification().waitsForUser()
                    && item.connectionId().equals(other.connectionId())) {
                matching.add(other);
            }
        }
        return matching;
    }

    /** Whether the bar offers Connect…: some tab waits for a password, a key or the vault. */
    boolean offersConnect() {
        return nextToConnect().isPresent();
    }

    /** Whether the bar offers Unlock Vault…: some tab waits for the vault and it is still locked. */
    boolean offersUnlock(boolean vaultLocked) {
        return vaultLocked && !waiting(Classification.NEEDS_UNLOCK).isEmpty();
    }

    /**
     * The bar's text, for example "Tabs not reopened: sign-in needed (2) · vault locked (1)": every
     * class with its count, in the order the user can act on them, so no plural forms are needed.
     */
    String summary(TabRestoreTriage.Texts texts) {
        StringJoiner parts = new StringJoiner(" · ");
        appendCount(parts, texts, COUNT_CREDENTIALS_KEY, Classification.NEEDS_CREDENTIALS);
        appendCount(parts, texts, COUNT_UNLOCK_KEY, Classification.NEEDS_UNLOCK);
        appendCount(parts, texts, COUNT_BLOCKED_KEY, Classification.BLOCKED);
        appendCount(parts, texts, COUNT_MISSING_KEY, Classification.MISSING);
        return texts.get(SUMMARY_KEY, parts.toString());
    }

    /** One line of the bar's details, for example "web-01: needs a password". */
    String itemText(Item<T> item, TabRestoreTriage.Texts texts) {
        TabRestoreTriage.Reason reason = item.outcome().reason();
        String why;
        if (reason == null) {
            why = "";
        } else if (reason == TabRestoreTriage.Reason.BLOCKED) {
            String target = item.outcome().blockedTarget();
            why = texts.get(reason.i18nKey(), target != null ? target : "?");
        } else {
            why = texts.get(reason.i18nKey());
        }
        return texts.get(ITEM_KEY, item.label(), why);
    }

    private void appendCount(StringJoiner parts, TabRestoreTriage.Texts texts, String key, Classification classification) {
        int count = waiting(classification).size();
        if (count > 0) {
            parts.add(texts.get(key, count));
        }
    }

    private boolean containsSame(Item<T> item) {
        for (Item<T> existing : items) {
            if (existing == item) {
                return true;
            }
        }
        return false;
    }
}
