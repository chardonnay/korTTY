package de.kortty.telemetry;

/**
 * Event-name constants for the anonymous usage statistics (Aptabase).
 * Names are snake_case; discriminating detail goes into props, not new names.
 */
public final class TelemetryEvents {

    /** Guide chapter location for the anonymous data / telemetry documentation. */
    public static final String GUIDE_LOCATION = "about/anonymous-data.html";

    // Lifecycle
    public static final String APP_STARTED = "app_started";
    public static final String APP_CRASH_DETECTED = "app_crash_detected";
    public static final String APP_ERROR = "app_error";
    public static final String USAGE_SNAPSHOT = "usage_snapshot";
    public static final String AI_PROFILE_SNAPSHOT = "ai_profile_snapshot";

    // Windows / tabs / terminal
    public static final String WINDOW_OPENED = "window_opened";
    public static final String TERMINAL_TAB_OPENED = "terminal_tab_opened";
    public static final String TERMINAL_SPLIT_CREATED = "terminal_split_created";
    public static final String BROADCAST_TOGGLED = "broadcast_toggled";
    public static final String FULLSCREEN_ENTERED = "fullscreen_entered";
    public static final String FILE_LOADED_AS_TEXT = "file_loaded_as_text";
    public static final String TERMINAL_LOG_STARTED = "terminal_log_started";
    public static final String TERMINAL_EFFECT_APPLIED = "terminal_effect_applied";

    // Projects / backups / connections / panels
    public static final String PROJECT_ACTION = "project_action";
    public static final String BACKUP_ACTION = "backup_action";
    public static final String CONNECT_UI_OPENED = "connect_ui_opened";
    public static final String CONNECTION_TRANSFER = "connection_transfer";
    public static final String SFTP_OPENED = "sftp_opened";
    public static final String DASHBOARD_TOGGLED = "dashboard_toggled";
    public static final String DASHBOARD_ACTION = "dashboard_action";
    public static final String FILE_BROWSER_TOGGLED = "file_browser_toggled";
    public static final String FILE_BROWSER_ACTION = "file_browser_action";
    public static final String JOURNAL_LIVE_PANEL_TOGGLED = "journal_live_panel_toggled";

    // Security / settings
    public static final String SECURITY_MANAGER_OPENED = "security_manager_opened";
    public static final String SECURITY_ENTRY_CHANGED = "security_entry_changed";
    public static final String MASTER_PASSWORD_CHANGED = "master_password_changed";
    public static final String SETTING_CHANGED = "setting_changed";

    // Tools / teamwork
    public static final String TOOL_OPENED = "tool_opened";
    public static final String TEAMWORK_CONFIGURED = "teamwork_configured";

    // AI
    public static final String AI_CHAT_MESSAGE = "ai_chat_message";
    public static final String AI_AGENT_RUN_STARTED = "ai_agent_run_started";
    public static final String AI_PLAN_RUN_STARTED = "ai_plan_run_started";
    public static final String AI_CHAT_SAVED = "ai_chat_saved";
    public static final String AI_SAVED_CHAT_OPENED = "ai_saved_chat_opened";

    // Snippet editor
    public static final String SNIPPET_AI_ACTION = "snippet_ai_action";
    public static final String SNIPPET_ONELINER_USED = "snippet_oneliner_used";
    public static final String SNIPPET_HISTORY_USED = "snippet_history_used";
    public static final String SNIPPET_SAVED = "snippet_saved";

    // Guide / updates
    public static final String GUIDE_PAGE_VIEWED = "guide_page_viewed";
    public static final String GUIDE_AI_SEARCH = "guide_ai_search";
    public static final String GUIDE_CITATION_CLICKED = "guide_citation_clicked";
    public static final String UPDATE_CHECK_CLICKED = "update_check_clicked";

    private TelemetryEvents() {
    }
}
