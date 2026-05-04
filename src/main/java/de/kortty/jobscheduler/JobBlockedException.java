package de.kortty.jobscheduler;

public class JobBlockedException extends Exception {

    public JobBlockedException(String message) {
        super(message);
    }

    public JobBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
