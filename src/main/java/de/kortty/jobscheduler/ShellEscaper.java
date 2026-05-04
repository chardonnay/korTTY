package de.kortty.jobscheduler;

final class ShellEscaper {

    private ShellEscaper() {
    }

    static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
