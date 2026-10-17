package de.kortty.ui;

import de.kortty.core.AutoBackupService;
import de.kortty.model.GlobalSettings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/** The one-line status of the automatic backup, for the Backup tab and the desktop notification. */
public final class AutoBackupStatusText {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private AutoBackupStatusText() { }

    /** Why the automatic backup did or did not run, in the user's language. */
    public static String describe(AutoBackupService.Outcome outcome, GlobalSettings settings) {
        if (outcome == null) {
            return "";
        }
        return switch (outcome.status()) {
            case DISABLED -> I18n.get("settings.backup.auto.status.disabled");
            case NO_DIRECTORY -> I18n.get("settings.backup.auto.status.noDirectory");
            case DIRECTORY_MISSING -> I18n.get("settings.backup.auto.status.directoryMissing",
                String.valueOf(settings.getAutoBackupDirectory()));
            case NO_ENCRYPTION_KEY -> settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.GPG
                ? I18n.get("settings.backup.auto.status.noGpgKey")
                : I18n.get("settings.backup.auto.status.noCredential");
            case WAITING_FOR_UNLOCK -> I18n.get("settings.backup.auto.status.waitingForUnlock");
            case FAILED -> I18n.get("settings.backup.auto.status.lastError", String.valueOf(outcome.detail()));
            case OK, NOT_DUE -> schedule(settings, outcome.nextRun());
        };
    }

    /** "Last automatic backup: … · Next: …" from the stored timestamp and the computed next run. */
    public static String schedule(GlobalSettings settings, Instant nextRun) {
        StringBuilder text = new StringBuilder();
        if (settings.getLastAutoBackupAt() > 0) {
            text.append(I18n.get("settings.backup.auto.status.lastRun", stamp(Instant.ofEpochMilli(settings.getLastAutoBackupAt()))));
        } else {
            text.append(I18n.get("settings.backup.auto.status.neverRun"));
        }
        if (nextRun != null) {
            text.append(" · ").append(I18n.get("settings.backup.auto.status.nextRun", stamp(nextRun)));
        }
        return text.toString();
    }

    private static String stamp(Instant instant) {
        return STAMP.format(instant.atZone(ZoneId.systemDefault()));
    }
}
