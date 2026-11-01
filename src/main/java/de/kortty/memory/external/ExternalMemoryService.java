package de.kortty.memory.external;

import de.kortty.core.AiSecretGuard;
import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The reference memory files as the rest of korTTY sees them: approval and review, the status of each approved file, and
 * the sources a request sends. Only approved text that still matches its approved digest is sent; a change of the file
 * stops it until the user approves the new text again.
 *
 * <p>Every method that reads a file does its IO on the calling thread, so the UI calls them from a background thread.
 * {@link #sourcesFor} runs for each prompt built, which costs one stat per approved file.</p>
 */
public final class ExternalMemoryService {

    /** Why a file cannot be approved, or why an approved file is not sent. */
    public enum Problem {
        NOT_FOUND,
        NOT_MARKDOWN,
        OUTSIDE_ROOT,
        NETWORK_FILE_SYSTEM,
        POLICY,
        TOO_LARGE,
        BINARY,
        SECRET,
        CHANGED,
        DUPLICATE
    }

    /**
     * A file read for approval: the text the user reviews and the digest the approval records.
     *
     * @param file       the canonical file, or the file as given when it was refused
     * @param root       the canonical folder the file was checked under, or the folder as given when refused
     * @param repository the Git repository the file lies in, if any
     * @param sha256     the digest of the file's bytes, or {@code null} when the file is too large
     * @param document   the read document, or {@code null} when the file was refused before it was read
     * @param problem    why the file cannot be approved, or empty when it can
     */
    public record Review(Path file, Path root, Optional<Path> repository, String sha256,
                         ExternalMemoryReader.Document document, Optional<Problem> problem) {

        public Review {
            repository = repository == null ? Optional.empty() : repository;
            problem = problem == null ? Optional.empty() : problem;
        }

        /** Whether the user may approve this text. */
        public boolean approvable() {
            return problem.isEmpty();
        }

        /** The possible secret the review found, when the problem is {@link Problem#SECRET}. */
        public Optional<AiSecretGuard.Finding> secret() {
            return document == null ? Optional.empty() : document.secret();
        }
    }

    /** An approval the service refused, with the reason for the dialog to show. */
    public static final class RefusedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;
        private final Problem problem;

        public RefusedException(Problem problem) {
            super("The memory file cannot be approved: " + problem);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** What checking one entry found: its status, the reason code and the source to send, when it is sent. */
    private record Evaluation(ExternalMemoryStatus status, String reason, Optional<ExternalSource> source) {
    }

    private static final Logger logger = LoggerFactory.getLogger(ExternalMemoryService.class);
    private static final String SECRET_REASON_PREFIX = "SECRET:";
    private static volatile ExternalMemoryService instance;

    private final ExternalMemoryRegistry registry;
    private final ExternalMemoryReader reader = new ExternalMemoryReader();
    private final ExternalMemoryAccess access;
    private final ExternalMemoryDiscovery discovery;
    private final Clock clock;
    private volatile Predicate<Path> policyGate = path -> true;

    /**
     * @param registry the approvals, kept in the memory directory
     * @param home     the user's home folder, the root of the files the user may pick without a project folder
     */
    public ExternalMemoryService(ExternalMemoryRegistry registry, Path home) {
        this(registry, home, Clock.systemUTC());
    }

    ExternalMemoryService(ExternalMemoryRegistry registry, Path home, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.access = new ExternalMemoryAccess(home, this::policyAllows);
        this.discovery = new ExternalMemoryDiscovery(access);
    }

    /** The application's service, created on first use in the memory directory of the default store. */
    public static ExternalMemoryService get() {
        ExternalMemoryService current = instance;
        if (current == null) {
            synchronized (ExternalMemoryService.class) {
                current = instance;
                if (current == null) {
                    current = new ExternalMemoryService(ExternalMemoryRegistry.inMemoryDirectory(
                        MemoryService.get().store().memoryDir()), Path.of(System.getProperty("user.home")));
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Replaces the application's service; tests use it with a registry in a temporary directory. */
    public static void replaceInstanceForTesting(ExternalMemoryService service) {
        instance = service;
    }

    /** The enterprise policy's check for a canonical file; read on every check, so a reloaded policy applies at once. */
    public void setPolicyGate(Predicate<Path> gate) {
        policyGate = gate;
    }

    public ExternalMemoryAccess access() {
        return access;
    }

    public ExternalMemoryDiscovery discovery() {
        return discovery;
    }

    /** The approvals as they were last checked. */
    public List<ExternalMemoryEntry> entries() {
        return registry.entries();
    }

    /**
     * Checks every approval now and stores the new statuses. A file that holds a secret is switched off. Returns the
     * entries after the check. The files are read, so call it off the FX thread.
     */
    public List<ExternalMemoryEntry> refresh() {
        List<ExternalMemoryEntry> checked = new ArrayList<>();
        for (ExternalMemoryEntry entry : registry.entries()) {
            ExternalMemoryEntry settled = settle(entry.id(), evaluate(entry));
            if (settled != null) {
                checked.add(settled);
            }
        }
        return checked;
    }

    /**
     * The sources a request for {@code connectionKey} sends: the active, approved files of the global scope and of that
     * connection that still match their approved digest. Each is checked now. Never throws: a failure leaves the file out.
     */
    public List<ExternalSource> sourcesFor(String connectionKey) {
        List<ExternalSource> sources = new ArrayList<>();
        for (ExternalMemoryEntry entry : registry.entries()) {
            if (!entry.active() || !entry.appliesTo(connectionKey)) {
                continue;
            }
            Evaluation evaluation = evaluate(entry);
            settle(entry.id(), evaluation);
            evaluation.source().ifPresent(sources::add);
        }
        return sources;
    }

    /** Reads {@code file} for approval under {@code root}. Refusals come back as a review with a problem, not as an exception. */
    public Review review(Path file, Path root) throws IOException {
        ExternalMemoryAccess.Outcome outcome = access.check(file, root);
        if (outcome.refusal().isPresent()) {
            return new Review(file, root, Optional.empty(), null, null, Optional.of(problemOf(outcome.refusal().get())));
        }
        Path real = outcome.file();
        ExternalMemoryReader.Document document = reader.read(real);
        Optional<Problem> problem = Optional.empty();
        if (document.tooLarge()) {
            problem = Optional.of(Problem.TOO_LARGE);
        } else if (document.binary()) {
            problem = Optional.of(Problem.BINARY);
        } else if (document.secret().isPresent()) {
            problem = Optional.of(Problem.SECRET);
        }
        return new Review(real, outcome.root(), ExternalMemoryAccess.gitRepositoryRoot(real), document.sha256(),
            document, problem);
    }

    /**
     * Approves a reviewed file for a tool and a scope ({@code connectionId} null for the global scope). The file is read
     * again first, so the approved digest is the digest of the text the user reviewed.
     *
     * @throws RefusedException the file is not approvable, changed since the review, or is already approved for the scope
     */
    public ExternalMemoryEntry approve(Review review, ExternalMemoryTool tool, String connectionId) throws IOException {
        requireApprovable(review);
        String scope = connectionId == null || connectionId.isBlank() ? null : connectionId.strip();
        if (registry.contains(review.file().toString(), scope)) {
            throw new RefusedException(Problem.DUPLICATE);
        }
        requireUnchanged(review);
        return registry.add(review.file().toString(), review.root().toString(), tool, scope, review.sha256(),
            Instant.now(clock));
    }

    /**
     * Approves the reviewed text of an entry again, after the file changed. The entry must be the same file.
     *
     * @throws RefusedException the file is not approvable, or changed since the review
     */
    public Optional<ExternalMemoryEntry> reapprove(String entryId, Review review) throws IOException {
        requireApprovable(review);
        requireUnchanged(review);
        Instant now = Instant.now(clock);
        return registry.update(entryId, entry -> entry.path().equals(review.file().toString())
            ? entry.withApproval(review.sha256(), now) : entry);
    }

    /** Switches an entry on or off. A switched-off entry is never sent. */
    public Optional<ExternalMemoryEntry> setActive(String entryId, boolean active) throws IOException {
        return registry.update(entryId, entry -> entry.withActive(active));
    }

    /** Removes an approval. The reference file itself is not changed. */
    public boolean remove(String entryId) throws IOException {
        return registry.remove(entryId);
    }

    /** The approved text's source label, as the prompt fence shows it: the tool and the file name. */
    static String labelOf(ExternalMemoryEntry entry) {
        Path name = Path.of(entry.path()).getFileName();
        return entry.tool().displayName() + ": " + (name == null ? entry.path() : name.toString());
    }

    private Evaluation evaluate(ExternalMemoryEntry entry) {
        if (entry.root().isBlank()) {
            // Never resolve a missing root to the working directory: an entry without a root is not sent.
            return new Evaluation(ExternalMemoryStatus.REFUSED_PATH, ExternalMemoryAccess.Refusal.OUTSIDE_ROOT.name(),
                Optional.empty());
        }
        ExternalMemoryAccess.Outcome outcome = access.check(Path.of(entry.path()), Path.of(entry.root()));
        if (outcome.refusal().isPresent()) {
            ExternalMemoryAccess.Refusal refusal = outcome.refusal().get();
            if (refusal == ExternalMemoryAccess.Refusal.NOT_FOUND) {
                return new Evaluation(ExternalMemoryStatus.MISSING, "", Optional.empty());
            }
            return new Evaluation(ExternalMemoryStatus.REFUSED_PATH, problemOf(refusal).name(), Optional.empty());
        }
        ExternalMemoryReader.Document document;
        try {
            document = reader.read(outcome.file());
        } catch (NoSuchFileException gone) {
            return new Evaluation(ExternalMemoryStatus.MISSING, "", Optional.empty());
        } catch (IOException | RuntimeException e) {
            logger.warn("A memory file could not be read; it is not sent");
            return new Evaluation(ExternalMemoryStatus.CHANGED, "UNREADABLE", Optional.empty());
        }
        if (document.secret().isPresent()) {
            AiSecretGuard.Finding finding = document.secret().get();
            return new Evaluation(ExternalMemoryStatus.REFUSED_SECRET,
                SECRET_REASON_PREFIX + finding.line() + ":" + finding.category().name(), Optional.empty());
        }
        if (!document.readable()) {
            return new Evaluation(ExternalMemoryStatus.CHANGED, document.tooLarge() ? "TOO_LARGE" : "BINARY",
                Optional.empty());
        }
        if (!document.sha256().equals(entry.approvedSha256())) {
            return new Evaluation(ExternalMemoryStatus.CHANGED, "CHANGED", Optional.empty());
        }
        return new Evaluation(ExternalMemoryStatus.OK, "",
            Optional.of(new ExternalSource(labelOf(entry), document.promptText(), entry.tool())));
    }

    private ExternalMemoryEntry settle(String entryId, Evaluation evaluation) {
        try {
            return registry.update(entryId, current -> {
                ExternalMemoryEntry next = current.withStatus(evaluation.status(), evaluation.reason());
                return evaluation.status() == ExternalMemoryStatus.REFUSED_SECRET ? next.withActive(false) : next;
            }).orElse(null);
        } catch (IOException e) {
            logger.warn("The status of a memory file could not be stored");
            return registry.find(entryId).orElse(null);
        }
    }

    private boolean policyAllows(Path canonicalFile) {
        return policyGate.test(canonicalFile);
    }

    private void requireApprovable(Review review) {
        if (review.problem().isPresent()) {
            throw new RefusedException(review.problem().get());
        }
    }

    private void requireUnchanged(Review review) throws IOException {
        String now = reader.read(review.file()).sha256();
        if (now == null || !now.equals(review.sha256())) {
            throw new RefusedException(Problem.CHANGED);
        }
    }

    private static Problem problemOf(ExternalMemoryAccess.Refusal refusal) {
        return switch (refusal) {
            case NOT_FOUND -> Problem.NOT_FOUND;
            case NOT_MARKDOWN -> Problem.NOT_MARKDOWN;
            case OUTSIDE_ROOT -> Problem.OUTSIDE_ROOT;
            case NETWORK_FILE_SYSTEM -> Problem.NETWORK_FILE_SYSTEM;
            case POLICY -> Problem.POLICY;
        };
    }
}
