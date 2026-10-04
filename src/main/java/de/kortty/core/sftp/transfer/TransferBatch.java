package de.kortty.core.sftp.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The items of one upload or download request (one drop, one button press) with their shared
 * conflict answers. Linked folders that were not followed are listed for the batch summary.
 */
public final class TransferBatch {

    private static final AtomicLong IDS = new AtomicLong();

    private final long id = IDS.incrementAndGet();
    private final TransferDirection direction;
    private final String targetFolder;
    private final ConflictAction conflictDefault;
    private final List<TransferItem> items = new CopyOnWriteArrayList<>();
    private final List<String> skippedLinks = new CopyOnWriteArrayList<>();
    private volatile ConflictPolicy policy;
    private volatile boolean finishReported;

    TransferBatch(TransferDirection direction, String targetFolder, ConflictAction conflictDefault) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.targetFolder = targetFolder == null ? "" : targetFolder;
        this.conflictDefault = conflictDefault;
        this.policy = new ConflictPolicy(conflictDefault);
    }

    public long id() {
        return id;
    }

    public TransferDirection direction() {
        return direction;
    }

    /** The folder the batch writes into, for display. */
    public String targetFolder() {
        return targetFolder;
    }

    /** The top-level items, in the order they were given. */
    public List<TransferItem> items() {
        return List.copyOf(items);
    }

    /** Every item: the top-level ones and, after each folder, its contents at any depth. */
    public List<TransferItem> allItems() {
        List<TransferItem> all = new ArrayList<>();
        for (TransferItem item : items) {
            collect(item, all);
        }
        return all;
    }

    static void collect(TransferItem item, List<TransferItem> into) {
        into.add(item);
        for (TransferItem child : item.children()) {
            collect(child, into);
        }
    }

    /** Source paths of linked folders that were not followed (D26), for the summary. */
    public List<String> skippedLinks() {
        return List.copyOf(skippedLinks);
    }

    /** The conflict answers of the batch. */
    public ConflictPolicy policy() {
        return policy;
    }

    /** Whether no item is waiting or working any more. */
    public boolean isFinished() {
        for (TransferItem item : items) {
            if (item.state().isActive()) {
                return false;
            }
        }
        return true;
    }

    /** How many files (not folders) are in {@code state}. */
    public int countFiles(TransferState state) {
        int count = 0;
        for (TransferItem item : allItems()) {
            if (item.kind() == TransferItem.Kind.FILE && item.state() == state) {
                count++;
            }
        }
        return count;
    }

    // ---- changed by the queue only ----

    void add(TransferItem item) {
        items.add(item);
    }

    boolean remove(TransferItem item) {
        return items.remove(item);
    }

    void addSkippedLink(String path) {
        skippedLinks.add(path);
    }

    /** A fresh policy after the batch was cancelled and is retried. */
    void renewPolicyIfCancelled() {
        if (policy.isCancelled()) {
            policy = new ConflictPolicy(conflictDefault);
        }
        finishReported = false;
    }

    /** True once per finish: the caller reports it. */
    boolean markFinishReported() {
        if (finishReported) {
            return false;
        }
        finishReported = true;
        return true;
    }
}
