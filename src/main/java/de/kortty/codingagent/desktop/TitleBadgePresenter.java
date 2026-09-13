package de.kortty.codingagent.desktop;

/**
 * Port through which {@link AppBadgeService} shows the blocked-agent count in the window title
 * ("(2) KorTTY", i18n key {@code codingAgent.title.badge}) where no icon badge is available.
 * Implemented by the UI bridge for every open main window; called on the JavaFX thread only.
 */
public interface TitleBadgePresenter {

    /** Shows {@code blockedCount} in every main-window title; {@code 0} restores the plain title. */
    void applyTitleCount(int blockedCount);
}
