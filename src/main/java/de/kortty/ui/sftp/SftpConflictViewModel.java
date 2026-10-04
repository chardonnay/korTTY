package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.ConflictAction;
import de.kortty.core.sftp.transfer.ConflictInfo;
import de.kortty.core.sftp.transfer.ConflictKind;
import de.kortty.ui.I18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the conflict dialog shows, computed without JavaFX so it can be tested headless.
 *
 * @param title      window title
 * @param header     the one-sentence question
 * @param source     the item being transferred
 * @param existing   the entry already at the target
 * @param notes      extra explanations (symbolic link, type mismatch, other owner)
 * @param actions    the buttons, in order
 * @param applyToAll the label of the "apply to all" box
 * @param rowLabels  the labels of the size and the modified row
 * @param newerLabel the marker shown next to the later modification time
 */
public record SftpConflictViewModel(
        String title,
        String header,
        Side source,
        Side existing,
        List<String> notes,
        List<Action> actions,
        String applyToAll,
        List<String> rowLabels,
        String newerLabel) {

    /** One column of the comparison. {@code newer} marks the side with the later time. */
    public record Side(String label, String size, String modified, boolean newer) {
    }

    /** One button. {@code cancel} marks the one the window's close box and Escape stand for. */
    public record Action(ConflictAction action, String label, boolean cancel) {
    }

    public static SftpConflictViewModel of(ConflictInfo info) {
        return of(info, ZoneId.systemDefault(), Locale.getDefault());
    }

    public static SftpConflictViewModel of(ConflictInfo info, ZoneId zone, Locale locale) {
        ConflictKind kind = info.kind();
        boolean sourceNewer = info.sourceMtimeMillis() != null && info.targetMtimeMillis() != null
            && info.sourceMtimeMillis() > info.targetMtimeMillis();
        boolean targetNewer = info.sourceMtimeMillis() != null && info.targetMtimeMillis() != null
            && info.targetMtimeMillis() > info.sourceMtimeMillis();
        Side source = new Side(I18n.get("sftp.conflict.source"),
            sizeText(info.sourceType(), false, info.sourceSize(), locale),
            timeText(info.sourceMtimeMillis(), zone, locale), sourceNewer);
        Side existing = new Side(I18n.get("sftp.conflict.existing"),
            sizeText(info.targetType(), info.targetIsSymlink(), info.targetSize(), locale),
            timeText(info.targetMtimeMillis(), zone, locale), targetNewer);

        List<String> notes = new ArrayList<>();
        if (kind == ConflictKind.SYMLINK) {
            notes.add(I18n.get("sftp.conflict.note.symlink"));
        } else if (kind == ConflictKind.TYPE_MISMATCH) {
            notes.add(I18n.get("sftp.conflict.note.typeMismatch"));
        } else if (info.ownerDiffers()) {
            notes.add(I18n.get("sftp.conflict.note.ownerDiffers"));
        }

        List<Action> actions = new ArrayList<>();
        for (ConflictAction action : info.allowedActions()) {
            actions.add(new Action(action, actionLabel(action, kind), action == ConflictAction.CANCEL_ALL));
        }
        return new SftpConflictViewModel(I18n.get("sftp.conflict.title"), header(info, kind), source, existing,
            List.copyOf(notes), List.copyOf(actions), I18n.get("sftp.conflict.applyToAll"),
            List.of(I18n.get("sftp.conflict.size"), I18n.get("sftp.conflict.modified")),
            I18n.get("sftp.conflict.newer"));
    }

    static String header(ConflictInfo info, ConflictKind kind) {
        String name = info.targetName();
        String folder = info.targetFolder();
        return switch (kind) {
            case SYMLINK -> I18n.get("sftp.conflict.header.symlink", name, folder);
            case TYPE_MISMATCH -> {
                if (info.targetType() == ConflictInfo.EntryType.FOLDER) {
                    yield I18n.get("sftp.conflict.header.folderExists", name, folder);
                }
                if (info.targetType() == ConflictInfo.EntryType.FILE) {
                    yield I18n.get("sftp.conflict.header.fileExists", name, folder);
                }
                yield I18n.get("sftp.conflict.header.special", name, folder);
            }
            default -> I18n.get("sftp.conflict.header.file", name, folder);
        };
    }

    static String actionLabel(ConflictAction action, ConflictKind kind) {
        return switch (action) {
            case OVERWRITE -> kind == ConflictKind.FOLDER
                ? I18n.get("sftp.conflict.action.merge") : I18n.get("sftp.conflict.action.overwrite");
            case SKIP -> I18n.get("sftp.conflict.action.skip");
            case RENAME -> I18n.get("sftp.conflict.action.rename");
            case CANCEL_ALL -> I18n.get("sftp.conflict.action.cancelAll");
        };
    }

    static String sizeText(ConflictInfo.EntryType type, boolean symlink, long bytes, Locale locale) {
        if (symlink) {
            return I18n.get("sftp.conflict.symlink");
        }
        if (type == ConflictInfo.EntryType.FOLDER) {
            return I18n.get("sftp.conflict.folder");
        }
        if (bytes < 0) {
            return I18n.get("sftp.conflict.unknown");
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(locale, "%.1f %s", value, units[unit]) + " (" + String.format(locale, "%,d", bytes) + " B)";
    }

    static String timeText(Long millis, ZoneId zone, Locale locale) {
        if (millis == null) {
            return I18n.get("sftp.conflict.unknown");
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli(millis));
    }
}
