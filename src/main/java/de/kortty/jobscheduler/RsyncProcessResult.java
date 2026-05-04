package de.kortty.jobscheduler;

record RsyncProcessResult(int exitCode, String stdout, String stderr) {
    boolean isSuccess() {
        return exitCode == 0;
    }
}
