package de.kortty.model;

import java.util.Locale;
import java.util.Set;

/**
 * Coarse classification of what a planned terminal-agent command does, used to pick a meaningful,
 * static row icon in the agent activity panel (e.g. a disk for file writes, a book for reads).
 * Pure string logic — no JavaFX — so it is unit-testable.
 */
public enum AgentActionCategory {
    WRITE,
    READ,
    EXECUTE,
    DIRECTORY,
    PACKAGE,
    SERVICE,
    NETWORK,
    INSPECT,
    GENERIC;

    private static final Set<String> WRITE_TOOLS = Set.of(
        "rm", "mv", "cp", "touch", "ln", "tee", "dd", "truncate", "install",
        "chmod", "chown", "chgrp", "unlink", "shred", "rsync");
    private static final Set<String> DIRECTORY_TOOLS = Set.of("mkdir", "rmdir", "cd", "pushd", "popd");
    private static final Set<String> READ_TOOLS = Set.of(
        "cat", "less", "more", "head", "tail", "grep", "egrep", "fgrep", "zgrep", "awk", "ls", "dir",
        "find", "locate", "stat", "file", "wc", "sort", "uniq", "cut", "tr", "nl", "tac", "readlink",
        "realpath", "basename", "dirname", "column", "jq", "yq", "diff", "cmp", "od", "xxd", "strings", "tree");
    private static final Set<String> EXECUTE_TOOLS = Set.of(
        "bash", "sh", "zsh", "dash", "ksh", "perl", "python", "python3", "ruby", "node", "nodejs",
        "php", "java", "go", "make", "ansible", "ansible-playbook");
    private static final Set<String> PACKAGE_TOOLS = Set.of(
        "apt", "apt-get", "aptitude", "dnf", "yum", "zypper", "pacman", "apk", "brew", "pip", "pip3",
        "pipx", "npm", "pnpm", "yarn", "gem", "cargo", "snap", "flatpak", "rpm", "dpkg");
    private static final Set<String> SERVICE_TOOLS = Set.of(
        "systemctl", "service", "launchctl", "rc-service", "initctl", "supervisorctl", "journalctl");
    private static final Set<String> NETWORK_TOOLS = Set.of(
        "curl", "wget", "ssh", "scp", "sftp", "ping", "ping6", "traceroute", "nc", "ncat", "netcat",
        "ss", "netstat", "ip", "ifconfig", "dig", "nslookup", "host", "telnet", "ftp");
    private static final Set<String> INSPECT_TOOLS = Set.of(
        "ps", "top", "htop", "free", "uptime", "uname", "whoami", "id", "env", "printenv", "hostname",
        "date", "df", "du", "lsblk", "lscpu", "lsof", "mount", "dmesg", "sysctl", "who", "groups");
    private static final Set<String> WRAPPER_TOOLS = Set.of(
        "sudo", "doas", "env", "command", "nohup", "time", "nice", "exec", "builtin", "setsid", "stdbuf", "ionice");

    /** Classifies a shell command into a coarse action category for icon selection. */
    public static AgentActionCategory classify(String command) {
        if (command == null || command.isBlank()) {
            return GENERIC;
        }
        // A write redirection (cat > file, here-doc, tee) makes any command a file write.
        if (hasWriteRedirection(command)) {
            return WRITE;
        }
        String word = leadingCommandWord(command);
        if (word == null || word.isEmpty()) {
            return GENERIC;
        }
        String base = word;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.toLowerCase(Locale.ROOT);
        // sed/perl with in-place editing mutate files.
        if ((base.equals("sed") || base.equals("perl")) && command.matches(".*\\s-i(\\S*)?(\\s.*|$)")) {
            return WRITE;
        }
        if (DIRECTORY_TOOLS.contains(base)) {
            return DIRECTORY;
        }
        if (WRITE_TOOLS.contains(base)) {
            return WRITE;
        }
        if (PACKAGE_TOOLS.contains(base)) {
            return PACKAGE;
        }
        if (SERVICE_TOOLS.contains(base)) {
            return SERVICE;
        }
        if (NETWORK_TOOLS.contains(base)) {
            return NETWORK;
        }
        if (INSPECT_TOOLS.contains(base)) {
            return INSPECT;
        }
        if (READ_TOOLS.contains(base)) {
            return READ;
        }
        if (EXECUTE_TOOLS.contains(base)
            || word.startsWith("./") || word.startsWith("../") || word.startsWith("/")) {
            return EXECUTE;
        }
        return GENERIC;
    }

    /** Returns the colored emoji shown for this category. */
    public String emoji() {
        return switch (this) {
            case WRITE -> "\uD83D\uDCBE";     // floppy disk
            case READ -> "\uD83D\uDCD6";      // open book
            case EXECUTE -> "\u25B6\uFE0F";   // play button
            case DIRECTORY -> "\uD83D\uDCC1"; // folder
            case PACKAGE -> "\uD83D\uDCE6";   // package
            case SERVICE -> "\u2699\uFE0F";   // gear
            case NETWORK -> "\uD83C\uDF10";   // globe
            case INSPECT -> "\uD83D\uDD0D";   // magnifier
            case GENERIC -> "\u25B6\uFE0F";   // play button
        };
    }

    private static boolean hasWriteRedirection(String command) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '\\' && i + 1 < command.length()) {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '>') {
                char prev = i > 0 ? command.charAt(i - 1) : '\0';
                char next = i + 1 < command.length() ? command.charAt(i + 1) : '\0';
                // Ignore fd-dup (>&) and arrows/option-like (->); a plain > or >> is a file write.
                if (prev == '-' || next == '&') {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** First real command word: skips leading env-assignments, wrapper commands, and stray flags. */
    private static String leadingCommandWord(String command) {
        for (String word : command.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int eq = word.indexOf('=');
            if (eq > 0 && word.substring(0, eq).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue; // environment assignment before the command
            }
            if (word.startsWith("-")) {
                continue; // option flag belonging to a preceding wrapper
            }
            String base = word;
            int slash = base.lastIndexOf('/');
            if (slash >= 0) {
                base = base.substring(slash + 1);
            }
            if (WRAPPER_TOOLS.contains(base.toLowerCase(Locale.ROOT))) {
                continue; // look past sudo/env/nohup/... to the wrapped command
            }
            return word;
        }
        return null;
    }
}
