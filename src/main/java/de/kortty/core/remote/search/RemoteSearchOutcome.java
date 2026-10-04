package de.kortty.core.remote.search;

/**
 * How a recursive remote search ended.
 *
 * @param strategy which strategy produced the results
 * @param results how many hits were delivered
 * @param stop why it ended
 */
public record RemoteSearchOutcome(Strategy strategy, int results, StopReason stop) {

    /** {@code find} over an exec channel, or the SFTP walk. */
    public enum Strategy { FIND, SFTP_WALK }

    /** Why the search ended. */
    public enum StopReason {
        /** Every folder within the depth was searched. */
        COMPLETED,
        /** The result limit was reached. */
        RESULT_LIMIT,
        /** The time limit was reached. */
        TIME_LIMIT,
        /** The SFTP walk read its maximum number of entries. */
        ENTRY_LIMIT,
        /** The user stopped it. */
        CANCELLED
    }
}
