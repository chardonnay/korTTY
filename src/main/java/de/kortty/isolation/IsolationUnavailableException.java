package de.kortty.isolation;

import java.io.IOException;

/**
 * Thrown when the organization demands an isolation level ({@code [rule.isolation] minimum}) that this
 * session cannot get, so it is not opened at all rather than opened with less.
 */
public class IsolationUnavailableException extends IOException {

    public IsolationUnavailableException(String message) {
        super(message);
    }
}
