package de.kortty.jobscheduler;

/**
 * Receives finished runs from {@link JobSchedulerService}. It is called on the job's worker thread
 * right after the result was saved, so an implementation must hand slow work (network, UI) to its
 * own executor. An exception it throws is logged and affects neither the run nor other listeners.
 */
@FunctionalInterface
public interface JobRunEventListener {
    void onJobRunFinished(JobRunEvent event);
}
