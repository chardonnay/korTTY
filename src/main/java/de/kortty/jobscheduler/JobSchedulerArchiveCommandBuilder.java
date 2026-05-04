package de.kortty.jobscheduler;

import java.util.List;

public class JobSchedulerArchiveCommandBuilder {

    public String build(JobAction action, String sudoPassword) {
        if (action == null) {
            throw new IllegalArgumentException("Archive action is required.");
        }
        String archivePath = requireNonBlank(action.getArchivePath(), "Archive path is required.");
        List<String> sources = action.getArchiveSourcePaths().stream()
            .filter(path -> path != null && !path.isBlank())
            .toList();
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one archive source path is required.");
        }
        String command = switch (action.getArchiveFormat()) {
            case ZIP -> buildZip(action, archivePath, sources);
            case ZIP_PASSWORD -> buildPasswordZip(action, archivePath, sources);
            case TAR -> buildTar(action, archivePath, sources, false);
            case TAR_BZ2 -> buildTar(action, archivePath, sources, true);
        };
        if (!action.isUseSudo()) {
            return command;
        }
        return sudoWrap(command, sudoPassword);
    }

    public static String sudoWrap(String command, String sudoPassword) {
        String sudo = sudoPassword != null && !sudoPassword.isBlank()
            ? "sudo -S -p '' sh -lc "
            : "sudo -n sh -lc ";
        return sudo + ShellEscaper.quote(command);
    }

    private String buildZip(JobAction action, String archivePath, List<String> sources) {
        StringBuilder command = new StringBuilder("zip -r -")
            .append(action.getArchiveCompressionLevel())
            .append(' ')
            .append(ShellEscaper.quote(archivePath))
            .append(' ');
        appendZipExcludes(command, action.getArchiveExcludePatterns());
        appendQuotedPaths(command, sources);
        return command.toString().trim();
    }

    private String buildPasswordZip(JobAction action, String archivePath, List<String> sources) {
        StringBuilder command = new StringBuilder("zip -er -")
            .append(action.getArchiveCompressionLevel())
            .append(' ')
            .append(ShellEscaper.quote(archivePath))
            .append(' ');
        appendZipExcludes(command, action.getArchiveExcludePatterns());
        appendQuotedPaths(command, sources);
        return command.toString().trim();
    }

    private String buildTar(JobAction action, String archivePath, List<String> sources, boolean bzip2) {
        StringBuilder command = new StringBuilder();
        if (bzip2) {
            command.append("BZIP2=-")
                .append(action.getArchiveCompressionLevel())
                .append(" tar -cjf ");
        } else {
            command.append("tar -cf ");
        }
        command.append(ShellEscaper.quote(archivePath)).append(' ');
        appendTarExcludes(command, action.getArchiveExcludePatterns());
        appendQuotedPaths(command, sources);
        return command.toString().trim();
    }

    private void appendZipExcludes(StringBuilder command, List<String> patterns) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                command.append("-x ").append(ShellEscaper.quote(pattern.trim())).append(' ');
            }
        }
    }

    private void appendTarExcludes(StringBuilder command, List<String> patterns) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                command.append("--exclude=").append(ShellEscaper.quote(pattern.trim())).append(' ');
            }
        }
    }

    private void appendQuotedPaths(StringBuilder command, List<String> paths) {
        for (String path : paths) {
            command.append(ShellEscaper.quote(path.trim())).append(' ');
        }
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
