package de.kortty.ui;

import de.kortty.model.AutomationJournalAiMode;
import de.kortty.model.AutomationRunStatus;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The small pills in the journal manager that say at a glance what a journal is: interactive,
 * job, AI Swarm or scheduled swarm; how its run ended; whether the AI summarized it; that later
 * identical runs point to it; that it is pinned; when it is deleted; that its model runs locally.
 * {@link #badges} decides (testable without JavaFX), {@link #render} draws.
 */
public final class SessionJournalBadges {

    /** Colour family of a badge; rendered as a translucent fill that works on light and dark themes. */
    public enum Tone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER, ACCENT }

    public record Badge(String text, Tone tone) {
    }

    private SessionJournalBadges() {
    }

    /**
     * The badges of a manager row.
     *
     * @param policyCapped an administrator caps automation journals (shown on automation rows)
     */
    public static List<Badge> badges(SessionJournalTreeSupport.Node node, OffsetDateTime now, boolean policyCapped) {
        List<Badge> badges = new ArrayList<>();
        if (node == null || node.journals().isEmpty()) {
            return badges;
        }
        SessionJournalMeta first = node.journals().get(0);
        boolean automation = first.isAutomation();
        if (!automation) {
            badges.add(new Badge(I18n.get("journal.manager.badge.interactive"), Tone.NEUTRAL));
        } else if (SessionJournalTreeSupport.isScheduledSwarm(node)) {
            badges.add(new Badge(I18n.get("journal.manager.badge.scheduledSwarm"), Tone.ACCENT));
        } else if (node.sourceKind() == SessionJournalSourceKind.SWARM) {
            badges.add(new Badge(I18n.get("journal.manager.badge.swarm"), Tone.ACCENT));
        } else {
            badges.add(new Badge(I18n.get("journal.manager.badge.job"), Tone.INFO));
        }
        if (node.anyLive()) {
            badges.add(new Badge("● " + I18n.get("journal.manager.badge.live"), Tone.SUCCESS));
        }
        AutomationRunStatus status = node.worstStatus();
        if (status != null) {
            badges.add(new Badge(I18n.get("journal.manager.badge.status." + status.name().toLowerCase(Locale.ROOT)),
                switch (status) {
                    case SUCCESS -> Tone.SUCCESS;
                    case FAILED -> Tone.DANGER;
                    case BLOCKED -> Tone.WARNING;
                    case CANCELLED -> Tone.NEUTRAL;
                }));
        }
        if (automation && first.getAiMode() != null) {
            AutomationJournalAiMode mode = first.getAiMode();
            badges.add(new Badge(I18n.get("journal.manager.badge.ai." + mode.name().toLowerCase(Locale.ROOT)),
                mode == AutomationJournalAiMode.OFF ? Tone.NEUTRAL : Tone.INFO));
        }
        if (node.allLocal() && node.aiCallCount() > 0) {
            badges.add(new Badge(I18n.get("journal.manager.badge.local"), Tone.SUCCESS));
        }
        if (node.duplicateRunCount() > 0) {
            badges.add(new Badge(I18n.get("journal.manager.badge.duplicates", node.duplicateRunCount() + 1), Tone.NEUTRAL));
        }
        if (node.anyPinned()) {
            badges.add(new Badge("📌 " + I18n.get("journal.manager.badge.pinned"), Tone.ACCENT));
        }
        OffsetDateTime expiry = node.nextExpiry();
        if (automation && expiry != null && now != null) {
            long hours = Duration.between(now, expiry).toHours();
            if (hours < 24) {
                badges.add(new Badge(I18n.get("journal.manager.badge.expiresToday"), Tone.WARNING));
            } else {
                long days = hours / 24;
                badges.add(new Badge(I18n.get("journal.manager.badge.expiresIn", days), days < 2 ? Tone.WARNING : Tone.NEUTRAL));
            }
        }
        if (automation && policyCapped) {
            badges.add(new Badge(I18n.get("journal.manager.badge.policy"), Tone.WARNING));
        }
        return badges;
    }

    /** The badges as a row of pills. */
    public static HBox render(List<Badge> badges) {
        HBox box = new HBox(4);
        box.setAlignment(Pos.CENTER_LEFT);
        for (Badge badge : badges) {
            Label pill = new Label(badge.text());
            pill.getStyleClass().addAll("journal-badge", "journal-badge-" + badge.tone().name().toLowerCase(Locale.ROOT));
            pill.setStyle(style(badge.tone()));
            pill.setTooltip(new Tooltip(badge.text()));
            pill.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            box.getChildren().add(pill);
        }
        return box;
    }

    private static String style(Tone tone) {
        String rgb = switch (tone) {
            case NEUTRAL -> "148, 163, 184";
            case INFO -> "56, 189, 248";
            case SUCCESS -> "34, 197, 94";
            case WARNING -> "245, 158, 11";
            case DANGER -> "239, 68, 68";
            case ACCENT -> "168, 85, 247";
        };
        return "-fx-background-color: rgba(" + rgb + ", 0.18); -fx-border-color: rgba(" + rgb + ", 0.55);"
            + " -fx-background-radius: 999; -fx-border-radius: 999; -fx-padding: 0 7 0 7; -fx-font-size: 0.82em;";
    }
}
