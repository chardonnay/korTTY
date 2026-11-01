package de.kortty.memory;

import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryBlockBuilder.Input;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.external.ExternalMemoryTool;
import de.kortty.model.AiTokenizerType;
import de.kortty.policy.UserMemoryMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The process-wide entry point for prompt building: returns the budgeted memory block for a
 * connection. Memory files are re-read only when their size or modification time changes, and a
 * block is reused while its inputs keep the same digests. Reads that fail, and suppliers that
 * throw, leave the request without memory rather than failing it.
 *
 * <p>The environment variable {@value #KILL_SWITCH_VARIABLE}={@code off} turns memory off for
 * support, demos and CI, without touching the files.</p>
 *
 * <p>The enterprise policy gates the service through suppliers that are read on every call, so a
 * reloaded policy applies at once. A policy gate that cannot be read fails closed, and until the
 * application sets the suppliers the memory is off: nothing is read and no write is allowed. Organisation
 * memory is sent whenever the policy allows the memory, whatever the user's switch, the user-memory mode and
 * the kill switch say; the user's own files and external files need all three of them.</p>
 */
public final class MemoryService {

    public static final String KILL_SWITCH_VARIABLE = "KORTTY_AI_MEMORY";
    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final int BLOCK_CACHE_SIZE = 32;
    private static final long PREVIEW_REFRESH_MILLIS = 5_000;

    private static volatile MemoryService instance;

    private final MemoryStore store;
    private final Function<String, String> environment;
    private volatile BooleanSupplier enabled = () -> true;
    private volatile Supplier<List<OrganizationRule>> organization = List::of;
    private volatile Function<String, List<ExternalSource>> external = connectionId -> List.of();
    private volatile BooleanSupplier aiMemoryAllowedGate = () -> false;
    private volatile Supplier<UserMemoryMode> userMemoryModeGate = () -> UserMemoryMode.DISABLED;
    private volatile BooleanSupplier externalFilesGate = () -> false;
    private volatile Supplier<OptionalInt> budgetCapGate = OptionalInt::empty;
    private final Map<String, CachedDocument> documents = new HashMap<>();
    private final Map<BlockKey, MemoryBlock> blocks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, MemoryBlock> eldest) {
            return size() > BLOCK_CACHE_SIZE;
        }
    };
    private final Map<String, Preview> previews = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Preview> eldest) {
            return size() > BLOCK_CACHE_SIZE;
        }
    };
    private final Set<String> previewsInProgress = new HashSet<>();
    private final ExecutorService previewRefresher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kortty-memory-preview");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryService(MemoryStore store, Function<String, String> environment) {
        this.store = store;
        this.environment = environment;
        store.addChangeListener(scope -> invalidate(scope));
        store.setWriteGate(this::isUserMemoryWritable);
    }

    /** The application's service, created on first use from the default store. */
    public static MemoryService get() {
        MemoryService current = instance;
        if (current == null) {
            synchronized (MemoryService.class) {
                current = instance;
                if (current == null) {
                    current = new MemoryService(MemoryStore.defaultStore(), System::getenv);
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Replaces the application's service; tests use it with a store in a temporary directory. */
    public static void replaceInstanceForTesting(MemoryService service) {
        instance = service;
    }

    public MemoryStore store() {
        return store;
    }

    /** Memory is used when the kill switch is not set and the enabled supplier says so. */
    public boolean isEnabled() {
        String killSwitch = environment.apply(KILL_SWITCH_VARIABLE);
        return enabled.getAsBoolean() && (killSwitch == null || !"off".equalsIgnoreCase(killSwitch.strip()));
    }

    public void setEnabledSupplier(BooleanSupplier supplier) {
        enabled = supplier;
    }

    public void setOrganizationMemorySupplier(Supplier<List<OrganizationRule>> supplier) {
        organization = supplier;
    }

    public void setExternalSourcesSupplier(Function<String, List<ExternalSource>> supplier) {
        external = supplier;
    }

    /** The enterprise policy's switch for the korTTY memory: the AI feature and {@code ai-memory} allowed. */
    public void setAiMemoryAllowedSupplier(BooleanSupplier supplier) {
        aiMemoryAllowedGate = supplier;
    }

    /** The enterprise policy's user-memory mode; {@link UserMemoryMode#EDITABLE} when no supplier is set. */
    public void setUserMemoryModeSupplier(Supplier<UserMemoryMode> supplier) {
        userMemoryModeGate = supplier;
    }

    /** The enterprise policy's switch for external memory files. */
    public void setExternalFilesAllowedSupplier(BooleanSupplier supplier) {
        externalFilesGate = supplier;
    }

    /** The enterprise policy's cap on the memory budget in tokens; empty when the policy sets none. */
    public void setBudgetCapSupplier(Supplier<OptionalInt> supplier) {
        budgetCapGate = supplier;
    }

    /**
     * Whether the user's memory may be changed: the korTTY memory is allowed by the policy and the user-memory
     * mode is {@link UserMemoryMode#EDITABLE}. A read-only or disabled mode, or a denied feature, is false. The
     * store refuses every write while this is false, see {@link MemoryStore#setWriteGate}.
     */
    public boolean isUserMemoryWritable() {
        return aiMemoryAllowed() && userMemoryMode() == UserMemoryMode.EDITABLE;
    }

    /**
     * The memory block for a request in {@code connectionId}, or for the global memory alone when
     * the id is null or blank. The budget is the smaller of {@code budgetTokens} and the policy's cap.
     */
    public MemoryBlock blockFor(String connectionId, AiTokenizerType tokenizer, int budgetTokens) {
        return blockFor(connectionId, tokenizer, budgetTokens, Set.of());
    }

    /**
     * As {@link #blockFor(String, AiTokenizerType, int)}, without the reference files of the tools in
     * {@code excludedExternal}: the tools whose CLI reads those files itself, see
     * {@link de.kortty.memory.external.LocalCliMemoryReads}.
     */
    public MemoryBlock blockFor(String connectionId, AiTokenizerType tokenizer, int budgetTokens,
                                Set<ExternalMemoryTool> excludedExternal) {
        int budget = Math.min(budgetTokens, budgetCap());
        if (budget <= 0 || !aiMemoryAllowed()) {
            return MemoryBlock.empty();
        }
        String connectionKey = connectionId == null || connectionId.isBlank() ? null : connectionId.strip();
        boolean userReadable = userMemoryReadable();
        try {
            List<OrganizationRule> organizationRules = organizationRules();
            Document global = userReadable ? document(MemoryScope.global()) : null;
            Document connection = userReadable && connectionKey != null
                ? document(MemoryScope.connection(connectionKey)) : null;
            Set<ExternalMemoryTool> excluded = excludedExternal == null ? Set.of() : excludedExternal;
            List<ExternalSource> externalSources = userReadable && externalFilesAllowed()
                ? externalSources(connectionKey, excluded) : List.of();
            BlockKey key = new BlockKey(connectionKey, tokenizer, budget, digestOf(global),
                digestOf(connection), organizationRules, externalSources);
            synchronized (this) {
                MemoryBlock cached = blocks.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            MemoryBlock block = MemoryBlockBuilder.build(new Input(organizationRules,
                global == null ? null : global.text(), connection == null ? null : connection.text(),
                externalSources), budget, tokenizer);
            synchronized (this) {
                blocks.put(key, block);
            }
            return block;
        } catch (IOException | RuntimeException e) {
            logger.warn("Memory could not be read; the request is sent without it", e);
            return MemoryBlock.empty();
        }
    }

    /**
     * The block last computed for this connection, tokenizer and budget, read without touching any file, so the
     * token preview can run on the UI thread. The first call returns an empty block and starts the computation on
     * a background thread; a block older than a few seconds is recomputed the same way, and the caller keeps
     * getting the older block until the new one is ready.
     */
    public MemoryBlock cachedBlockFor(String connectionId, AiTokenizerType tokenizer, int budgetTokens) {
        return cachedBlockFor(connectionId, tokenizer, budgetTokens, Set.of());
    }

    /** As {@link #cachedBlockFor(String, AiTokenizerType, int)}, without the reference files of {@code excludedExternal}. */
    public MemoryBlock cachedBlockFor(String connectionId, AiTokenizerType tokenizer, int budgetTokens,
                                      Set<ExternalMemoryTool> excludedExternal) {
        Set<ExternalMemoryTool> excluded = excludedExternal == null ? Set.of() : Set.copyOf(excludedExternal);
        String key = previewKey(connectionId, tokenizer, budgetTokens, excluded);
        Preview preview;
        synchronized (this) {
            preview = previews.get(key);
        }
        if (preview == null || System.currentTimeMillis() - preview.computedAt() > PREVIEW_REFRESH_MILLIS) {
            refreshPreviewInBackground(key, connectionId, tokenizer, budgetTokens, excluded);
        }
        return preview == null ? MemoryBlock.empty() : preview.block();
    }

    private void refreshPreviewInBackground(String key, String connectionId, AiTokenizerType tokenizer,
                                            int budgetTokens, Set<ExternalMemoryTool> excluded) {
        synchronized (this) {
            if (!previewsInProgress.add(key)) {
                return;
            }
        }
        previewRefresher.execute(() -> {
            try {
                MemoryBlock block = blockFor(connectionId, tokenizer, budgetTokens, excluded);
                synchronized (this) {
                    previews.put(key, new Preview(block, System.currentTimeMillis()));
                }
            } finally {
                synchronized (this) {
                    previewsInProgress.remove(key);
                }
            }
        });
    }

    private static String previewKey(String connectionId, AiTokenizerType tokenizer, int budgetTokens,
                                     Set<ExternalMemoryTool> excluded) {
        String connectionKey = connectionId == null || connectionId.isBlank() ? "" : connectionId.strip();
        String excludedNames = excluded.stream().map(Enum::name).sorted().reduce("", (a, b) -> a + "," + b);
        return connectionKey + "\n" + tokenizer + "\n" + budgetTokens + "\n" + excludedNames;
    }

    /** The user's own files are read only with the user's switch on, the mode not disabled and no kill switch. */
    private boolean userMemoryReadable() {
        return isEnabled() && userMemoryMode() != UserMemoryMode.DISABLED;
    }

    private boolean aiMemoryAllowed() {
        try {
            return aiMemoryAllowedGate.getAsBoolean();
        } catch (RuntimeException e) {
            logger.warn("The enterprise policy for the AI memory could not be read; the memory is not used", e);
            return false;
        }
    }

    private UserMemoryMode userMemoryMode() {
        try {
            UserMemoryMode mode = userMemoryModeGate.get();
            return mode == null ? UserMemoryMode.DISABLED : mode;
        } catch (RuntimeException e) {
            logger.warn("The enterprise policy for the user memory could not be read; the memory is not used", e);
            return UserMemoryMode.DISABLED;
        }
    }

    private boolean externalFilesAllowed() {
        try {
            return externalFilesGate.getAsBoolean();
        } catch (RuntimeException e) {
            logger.warn("The enterprise policy for external memory files could not be read", e);
            return false;
        }
    }

    private int budgetCap() {
        try {
            OptionalInt cap = budgetCapGate.get();
            return cap == null || cap.isEmpty() ? Integer.MAX_VALUE : cap.getAsInt();
        } catch (RuntimeException e) {
            logger.warn("The enterprise policy for the memory budget could not be read; the memory is not sent", e);
            return 0;
        }
    }

    private static String digestOf(Document document) {
        return document == null ? null : document.digest();
    }

    private Document document(MemoryScope scope) throws IOException {
        MemoryStore.Stamp stamp = store.stamp(scope);
        synchronized (this) {
            CachedDocument cached = documents.get(scope.key());
            if (cached != null && cached.stamp().equals(stamp)) {
                return new Document(cached.text(), cached.digest());
            }
        }
        MemoryStore.Snapshot snapshot = store.read(scope);
        synchronized (this) {
            documents.put(scope.key(), new CachedDocument(stamp, snapshot.text(), snapshot.sha256()));
        }
        return new Document(snapshot.text(), snapshot.sha256());
    }

    private synchronized void invalidate(MemoryScope scope) {
        documents.remove(scope.key());
    }

    private List<OrganizationRule> organizationRules() {
        try {
            List<OrganizationRule> rules = organization.get();
            return rules == null ? List.of() : List.copyOf(rules);
        } catch (RuntimeException e) {
            logger.warn("The organisation memory could not be read", e);
            return List.of();
        }
    }

    private List<ExternalSource> externalSources(String connectionKey, Set<ExternalMemoryTool> excluded) {
        try {
            List<ExternalSource> sources = external.apply(connectionKey);
            if (sources == null) {
                return List.of();
            }
            return sources.stream().filter(source -> !excluded.contains(source.tool())).toList();
        } catch (RuntimeException e) {
            logger.warn("The external memory files could not be read", e);
            return List.of();
        }
    }

    private record Document(String text, String digest) {
    }

    private record CachedDocument(MemoryStore.Stamp stamp, String text, String digest) {
    }

    private record Preview(MemoryBlock block, long computedAt) {
    }

    private record BlockKey(String connectionId, AiTokenizerType tokenizer, int budgetTokens, String globalDigest,
                            String connectionDigest, List<OrganizationRule> organization,
                            List<ExternalSource> external) {
    }
}
