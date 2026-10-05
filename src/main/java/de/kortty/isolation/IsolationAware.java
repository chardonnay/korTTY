package de.kortty.isolation;

/** A terminal connector that knows how isolated its session is. */
public interface IsolationAware {

    /** The session's isolation; {@link IsolationReport#NONE} before it is connected. */
    IsolationReport isolationReport();
}
