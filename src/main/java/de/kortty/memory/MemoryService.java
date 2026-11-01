package de.kortty.memory;

import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryBlockBuilder.Input;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.model.AiTokenizerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
public final class MemoryService {

    public static final String KILL_SWITCH_VARIABLE = "KORTTY_AI_MEMORY";
    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);
    private static final int BLOCK_CACHE_SIZE = 32;

    private static volatile MemoryService instance;

    private final MemoryStore store;
    private final Function<String, String> environment;
    private volatile BooleanSupplier enabled = () -> true;
    private volatile Supplier<List<OrganizationRule>> organization = List::of;
    private volatile Function<String, List<ExternalSource>> external = connectionId -> List.of();
    private final Map<String, CachedDocument> documents = new HashMap<>();
    private final Map<BlockKey, MemoryBlock> blocks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, MemoryBlock> eldest) {
            return size() > BLOCK_CACHE_SIZE;
        }
    };

    public MemoryService(MemoryStore store, Function<String, String> environment) {
        this.store = store;
        this.environment = environment;
        store.addChangeListener(scope -> invalidate(scope));
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

    /**
     * The memory block for a request in {@code connectionId}, or for the global memory alone when
     * the id is null or blank.
     */
    public MemoryBlock blockFor(String connectionId, AiTokenizerType tokenizer, int budgetTokens) {
        if (!isEnabled() || budgetTokens <= 0) {
            return MemoryBlock.empty();
        }
        String connectionKey = connectionId == null || connectionId.isBlank() ? null : connectionId.strip();
        try {
            Document global = document(MemoryScope.global());
            Document connection = connectionKey == null ? null : document(MemoryScope.connection(connectionKey));
            List<OrganizationRule> organizationRules = organizationRules();
            List<ExternalSource> externalSources = externalSources(connectionKey);
            BlockKey key = new BlockKey(connectionKey, tokenizer, budgetTokens, global.digest(),
                connection == null ? null : connection.digest(), organizationRules, externalSources);
            synchronized (this) {
                MemoryBlock cached = blocks.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            MemoryBlock block = MemoryBlockBuilder.build(new Input(organizationRules, global.text(),
                connection == null ? null : connection.text(), externalSources), budgetTokens, tokenizer);
            synchronized (this) {
                blocks.put(key, block);
            }
            return block;
        } catch (IOException | RuntimeException e) {
            logger.warn("Memory could not be read; the request is sent without it", e);
            return MemoryBlock.empty();
        }
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

    private List<ExternalSource> externalSources(String connectionKey) {
        try {
            List<ExternalSource> sources = external.apply(connectionKey);
            return sources == null ? List.of() : List.copyOf(sources);
        } catch (RuntimeException e) {
            logger.warn("The external memory files could not be read", e);
            return List.of();
        }
    }

    private record Document(String text, String digest) {
    }

    private record CachedDocument(MemoryStore.Stamp stamp, String text, String digest) {
    }

    private record BlockKey(String connectionId, AiTokenizerType tokenizer, int budgetTokens, String globalDigest,
                            String connectionDigest, List<OrganizationRule> organization,
                            List<ExternalSource> external) {
    }
}
