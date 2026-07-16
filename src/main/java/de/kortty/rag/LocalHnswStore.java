package de.kortty.rag;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependency-free local HNSW store using cosine similarity.
 *
 * <p>Nodes are assigned deterministic exponentially distributed levels. Insertions navigate the
 * upper layers greedily and use {@code efConstruction} at every shared layer before establishing
 * bidirectional links. The base layer allows {@code 2 * M} links, while upper layers allow
 * {@code M}. Source replacement rebuilds a candidate graph separately in memory, fsyncs it to a
 * temporary snapshot and atomically swaps it; cancellation or failure therefore leaves the active
 * graph and its on-disk snapshot untouched.</p>
 *
 * <p>Filtered searches still traverse the graph through nodes from any source, but only active
 * source IDs enter the result heap. A per-source entry-point map seeds those searches so a sparse
 * active source does not require an exhaustive scan of every vector.</p>
 */
public final class LocalHnswStore implements RagVectorStore {
    public static final int DEFAULT_M = 16;
    public static final int DEFAULT_EF_CONSTRUCTION = 200;
    public static final int DEFAULT_EF_SEARCH = 64;
    private static final byte[] MAGIC = "KORTHNS1".getBytes(StandardCharsets.US_ASCII);
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION = 2;
    private static final int MAX_LEVEL = 32;
    private static final int MAX_SNAPSHOT_NODES = 5_000_000;
    private static final int MAX_SNAPSHOT_M = 10_000;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;
    private static final ConcurrentHashMap<Path, Object> JVM_SNAPSHOT_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;
    private final Path snapshotFile;
    private final Path lockFile;
    private final int dimensions;
    private final String embeddingModelId;
    private final int m;
    private final int efConstruction;
    private final int efSearch;
    private volatile Snapshot active;
    private volatile int lastSearchVisitedCount;

    public LocalHnswStore(Path directory, int dimensions, String embeddingModelId) throws IOException {
        this(directory, dimensions, embeddingModelId, DEFAULT_M, DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH);
    }

    public LocalHnswStore(
        Path directory,
        int dimensions,
        String embeddingModelId,
        int m,
        int efConstruction,
        int efSearch
    ) throws IOException {
        if (dimensions <= 0 || m < 2 || m > MAX_SNAPSHOT_M
            || efConstruction < m || efSearch <= 0) {
            throw new IllegalArgumentException("Invalid HNSW dimensions/parameters");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.snapshotFile = this.directory.resolve("index.hnsw");
        this.lockFile = this.directory.resolve("index.hnsw.lock");
        this.dimensions = dimensions;
        this.embeddingModelId = embeddingModelId == null ? "" : embeddingModelId;
        this.m = m;
        this.efConstruction = efConstruction;
        this.efSearch = efSearch;
        Files.createDirectories(this.directory);
        if (Files.isRegularFile(snapshotFile)) {
            ReadResult read = readSnapshot(snapshotFile);
            if (read.migrated()) {
                // Version-1 snapshots held only a base-layer graph. Rebuild and atomically promote
                // them once so all subsequent starts use the hierarchical representation.
                persistCandidate(read.snapshot(), CancellationToken.NONE);
            }
            this.active = read.snapshot();
        } else {
            this.active = Snapshot.empty();
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String embeddingModelId() {
        return embeddingModelId;
    }

    @Override
    public List<RagEmbeddedChunk> chunksForSource(String sourceId) {
        return active.nodes.stream()
            .filter(node -> node.value.chunk().sourceId().equals(sourceId))
            .map(Node::value)
            .toList();
    }

    public int size() {
        return active.nodes.size();
    }

    // Package-private diagnostics used by focused graph-regression tests.
    int graphLevelCount() {
        return active.maxLevel + 1;
    }

    int lastSearchVisitedCount() {
        return lastSearchVisitedCount;
    }

    @Override
    public synchronized void replaceSource(
        String sourceId,
        Collection<RagEmbeddedChunk> replacement,
        CancellationToken cancellation
    ) throws IOException {
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        token.throwIfCancelled();
        Object jvmLock = JVM_SNAPSHOT_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
        synchronized (jvmLock) {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                // Another korTTY process may have committed a different source since this object
                // was opened. Reload under the cross-process lock before deriving the candidate,
                // otherwise last-writer-wins would silently discard that update.
                Snapshot base = Files.isRegularFile(snapshotFile)
                    ? readSnapshot(snapshotFile).snapshot()
                    : Snapshot.empty();
                List<RagEmbeddedChunk> values = new ArrayList<>();
                for (Node node : base.nodes) {
                    if (!node.value.chunk().sourceId().equals(sourceId)) {
                        values.add(node.value);
                    }
                }
                if (replacement != null) {
                    for (RagEmbeddedChunk value : replacement) {
                        validate(value);
                        if (!value.chunk().sourceId().equals(sourceId)) {
                            throw new IllegalArgumentException("Replacement contains a different source id");
                        }
                        values.add(value);
                    }
                }
                Snapshot candidate = build(values, token);
                persistCandidate(candidate, token);
                active = candidate;
            }
        }
    }

    @Override
    public void removeSource(String sourceId, CancellationToken cancellation) throws IOException {
        replaceSource(sourceId, List.of(), cancellation);
    }

    @Override
    public List<RagSearchResult> search(
        float[] queryVector,
        int limit,
        Set<String> sourceIds,
        CancellationToken cancellation
    ) {
        Snapshot snapshot = active;
        if (limit <= 0 || snapshot.nodes.isEmpty()) {
            lastSearchVisitedCount = 0;
            return List.of();
        }
        float[] query = normalized(queryVector);
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        Set<String> filter = sourceIds == null || sourceIds.isEmpty() ? Set.of() : Set.copyOf(sourceIds);
        int searchWidth = Math.min(snapshot.nodes.size(), Math.max(efSearch, limit * 4));
        List<Integer> entryPoints = new ArrayList<>();

        if (filter.isEmpty()) {
            int baseEntry = descendToBase(query, snapshot.entryPoint, snapshot.maxLevel, snapshot, token);
            entryPoints.add(baseEntry);
        } else {
            for (String sourceId : filter) {
                Integer sourceEntry = snapshot.sourceEntryPoints.get(sourceId);
                if (sourceEntry == null) {
                    continue;
                }
                // Keep the source-owned seed as well as its upper-layer greedy destination. The
                // latter improves routing; the former guarantees that every requested source gets
                // a chance to enter the filtered result heap.
                entryPoints.add(sourceEntry);
                int level = snapshot.nodes.get(sourceEntry).level();
                entryPoints.add(descendToBase(query, sourceEntry, level, snapshot, token));
            }
            if (entryPoints.isEmpty()) {
                lastSearchVisitedCount = 0;
                return List.of();
            }
            searchWidth = Math.min(snapshot.nodes.size(), Math.max(searchWidth, entryPoints.size() * 4));
        }

        SearchOutcome outcome = searchBaseLayer(query, searchWidth, entryPoints, filter, snapshot, token);
        lastSearchVisitedCount = outcome.visitedCount;
        return outcome.hits.stream()
            .sorted(SCORED_BEST_FIRST)
            .limit(limit)
            .map(hit -> new RagSearchResult(hit.node.value.chunk(), hit.score,
                hit.node.value.chunk().citation()))
            .toList();
    }

    private int descendToBase(
        float[] query,
        int entryPoint,
        int entryLevel,
        Snapshot snapshot,
        CancellationToken token
    ) {
        int current = entryPoint;
        for (int layer = Math.min(entryLevel, snapshot.nodes.get(current).level()); layer > 0; layer--) {
            current = greedySearch(query, current, layer, snapshot.nodes, token);
        }
        return current;
    }

    private int greedySearch(
        float[] query,
        int entryPoint,
        int layer,
        List<Node> graph,
        CancellationToken token
    ) {
        int currentIndex = entryPoint;
        double currentScore = cosine(query, graph.get(currentIndex).value.rawVector());
        boolean improved;
        do {
            improved = false;
            token.throwIfCancelled();
            for (int neighborIndex : graph.get(currentIndex).neighborsAt(layer)) {
                Node neighbor = graph.get(neighborIndex);
                double score = cosine(query, neighbor.value.rawVector());
                if (isBetter(score, neighborIndex, currentScore, currentIndex)) {
                    currentIndex = neighborIndex;
                    currentScore = score;
                    improved = true;
                }
            }
        } while (improved);
        return currentIndex;
    }

    private SearchOutcome searchBaseLayer(
        float[] query,
        int ef,
        List<Integer> starts,
        Set<String> sourceFilter,
        Snapshot snapshot,
        CancellationToken token
    ) {
        PriorityQueue<ScoredNode> candidates = new PriorityQueue<>(SCORED_BEST_FIRST);
        PriorityQueue<ScoredNode> routingBest = new PriorityQueue<>(SCORED_WORST_FIRST);
        PriorityQueue<ScoredNode> acceptedBest = new PriorityQueue<>(SCORED_WORST_FIRST);
        Set<Integer> visited = new HashSet<>();
        Set<Integer> unexpandedSeeds = new HashSet<>();

        for (int startIndex : starts) {
            if (!visited.add(startIndex)) {
                continue;
            }
            Node start = snapshot.nodes.get(startIndex);
            ScoredNode scored = new ScoredNode(startIndex, start, cosine(query, start.value.rawVector()));
            candidates.add(scored);
            addBounded(routingBest, scored, ef);
            if (sourceFilter.isEmpty() || sourceFilter.contains(start.value.chunk().sourceId())) {
                addBounded(acceptedBest, scored, ef);
            }
            unexpandedSeeds.add(startIndex);
        }

        while (!candidates.isEmpty()) {
            token.throwIfCancelled();
            ScoredNode current = candidates.poll();
            unexpandedSeeds.remove(current.index);
            if (unexpandedSeeds.isEmpty() && routingBest.size() >= ef
                && isWorse(current, routingBest.peek())) {
                break;
            }
            for (int neighborIndex : current.node.neighborsAt(0)) {
                if (!visited.add(neighborIndex)) {
                    continue;
                }
                token.throwIfCancelled();
                Node neighbor = snapshot.nodes.get(neighborIndex);
                ScoredNode hit = new ScoredNode(neighborIndex, neighbor,
                    cosine(query, neighbor.value.rawVector()));
                boolean route = routingBest.size() < ef || isBetter(hit, routingBest.peek());
                if (route) {
                    candidates.add(hit);
                    addBounded(routingBest, hit, ef);
                }
                if (sourceFilter.isEmpty() || sourceFilter.contains(neighbor.value.chunk().sourceId())) {
                    addBounded(acceptedBest, hit, ef);
                }
            }
        }
        return new SearchOutcome(new ArrayList<>(acceptedBest), visited.size());
    }

    private Snapshot build(List<RagEmbeddedChunk> values, CancellationToken token) {
        if (values.isEmpty()) {
            return Snapshot.empty();
        }
        List<MutableNode> graph = new ArrayList<>(values.size());
        int entryPoint = -1;
        int maxLevel = -1;

        for (RagEmbeddedChunk value : values) {
            token.throwIfCancelled();
            validate(value);
            MutableNode inserted = new MutableNode(graph.size(), value, levelFor(value));
            graph.add(inserted);
            if (entryPoint < 0) {
                entryPoint = inserted.index;
                maxLevel = inserted.level;
                continue;
            }

            int current = entryPoint;
            for (int layer = maxLevel; layer > inserted.level; layer--) {
                current = greedyMutableSearch(value.rawVector(), current, layer, graph, token);
            }
            for (int layer = Math.min(inserted.level, maxLevel); layer >= 0; layer--) {
                List<ScoredMutableNode> candidates = searchMutableLayer(
                    value.rawVector(), List.of(current), efConstruction, layer, graph, token);
                List<ScoredMutableNode> selected = selectMutableNeighbors(
                    value.rawVector(), candidates, Math.min(m, candidates.size()));
                for (ScoredMutableNode hit : selected) {
                    inserted.addNeighbor(layer, hit.node.index);
                    hit.node.addNeighbor(layer, inserted.index);
                    pruneMutableNode(hit.node, layer, graph);
                }
                if (!selected.isEmpty()) {
                    current = selected.get(0).node.index;
                }
            }
            if (inserted.level > maxLevel) {
                entryPoint = inserted.index;
                maxLevel = inserted.level;
            }
        }

        List<Node> frozen = graph.stream().map(MutableNode::freeze).toList();
        return Snapshot.create(frozen, entryPoint, maxLevel);
    }

    private int greedyMutableSearch(
        float[] query,
        int entryPoint,
        int layer,
        List<MutableNode> graph,
        CancellationToken token
    ) {
        int currentIndex = entryPoint;
        double currentScore = cosine(query, graph.get(currentIndex).value.rawVector());
        boolean improved;
        do {
            improved = false;
            token.throwIfCancelled();
            for (int neighborIndex : graph.get(currentIndex).neighborsAt(layer)) {
                MutableNode neighbor = graph.get(neighborIndex);
                double score = cosine(query, neighbor.value.rawVector());
                if (isBetter(score, neighborIndex, currentScore, currentIndex)) {
                    currentIndex = neighborIndex;
                    currentScore = score;
                    improved = true;
                }
            }
        } while (improved);
        return currentIndex;
    }

    private List<ScoredMutableNode> searchMutableLayer(
        float[] query,
        List<Integer> starts,
        int ef,
        int layer,
        List<MutableNode> graph,
        CancellationToken token
    ) {
        PriorityQueue<ScoredMutableNode> candidates = new PriorityQueue<>(MUTABLE_BEST_FIRST);
        PriorityQueue<ScoredMutableNode> best = new PriorityQueue<>(MUTABLE_WORST_FIRST);
        Set<Integer> visited = new HashSet<>();
        for (int startIndex : starts) {
            if (!visited.add(startIndex)) {
                continue;
            }
            MutableNode start = graph.get(startIndex);
            ScoredMutableNode scored = new ScoredMutableNode(start,
                cosine(query, start.value.rawVector()));
            candidates.add(scored);
            addMutableBounded(best, scored, ef);
        }
        while (!candidates.isEmpty()) {
            token.throwIfCancelled();
            ScoredMutableNode current = candidates.poll();
            if (best.size() >= ef && isWorse(current, best.peek())) {
                break;
            }
            for (int neighborIndex : current.node.neighborsAt(layer)) {
                if (!visited.add(neighborIndex)) {
                    continue;
                }
                MutableNode neighbor = graph.get(neighborIndex);
                ScoredMutableNode hit = new ScoredMutableNode(neighbor,
                    cosine(query, neighbor.value.rawVector()));
                if (best.size() < ef || isBetter(hit, best.peek())) {
                    candidates.add(hit);
                    addMutableBounded(best, hit, ef);
                }
            }
        }
        return new ArrayList<>(best);
    }

    private List<ScoredMutableNode> selectMutableNeighbors(
        float[] query,
        List<ScoredMutableNode> candidates,
        int limit
    ) {
        if (limit == 0) {
            return List.of();
        }
        List<ScoredMutableNode> ordered = candidates.stream().sorted(MUTABLE_BEST_FIRST).toList();
        List<ScoredMutableNode> selected = new ArrayList<>(limit);
        List<ScoredMutableNode> deferred = new ArrayList<>();
        for (ScoredMutableNode candidate : ordered) {
            boolean diverse = true;
            for (ScoredMutableNode existing : selected) {
                if (cosine(candidate.node.value.rawVector(), existing.node.value.rawVector())
                    > cosine(query, candidate.node.value.rawVector())) {
                    diverse = false;
                    break;
                }
            }
            if (diverse) {
                selected.add(candidate);
                if (selected.size() == limit) {
                    return selected;
                }
            } else {
                deferred.add(candidate);
            }
        }
        for (ScoredMutableNode candidate : deferred) {
            selected.add(candidate);
            if (selected.size() == limit) {
                break;
            }
        }
        return selected;
    }

    private void pruneMutableNode(MutableNode node, int layer, List<MutableNode> graph) {
        int maxConnections = layer == 0 ? Math.multiplyExact(m, 2) : m;
        List<Integer> neighbors = node.neighborsAt(layer);
        if (neighbors.size() <= maxConnections) {
            return;
        }
        List<ScoredMutableNode> candidates = neighbors.stream()
            .map(graph::get)
            .map(neighbor -> new ScoredMutableNode(neighbor,
                cosine(node.value.rawVector(), neighbor.value.rawVector())))
            .toList();
        List<ScoredMutableNode> selected = selectMutableNeighbors(
            node.value.rawVector(), candidates, maxConnections);
        neighbors.clear();
        selected.forEach(hit -> neighbors.add(hit.node.index));
    }

    private int levelFor(RagEmbeddedChunk value) {
        long hash = 0xcbf29ce484222325L;
        String key = value.chunk().sourceId() + '\0' + value.chunk().id() + '\0'
            + value.chunk().documentHash();
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        for (byte valueByte : bytes) {
            hash ^= valueByte & 0xffL;
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        double uniform = ((hash >>> 11) + 1.0) * 0x1.0p-53;
        int level = (int) Math.floor(-Math.log(uniform) / Math.log(m));
        return Math.min(MAX_LEVEL, Math.max(0, level));
    }

    private void persistCandidate(Snapshot candidate, CancellationToken token) throws IOException {
        Path temporary = directory.resolve("index.hnsw.tmp-" + UUID.randomUUID());
        try {
            try (FileOutputStream file = new FileOutputStream(temporary.toFile());
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
                output.write(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(dimensions);
                writeString(output, embeddingModelId);
                output.writeInt(m);
                output.writeInt(candidate.entryPoint);
                output.writeInt(candidate.maxLevel);
                output.writeInt(candidate.nodes.size());
                for (Node node : candidate.nodes) {
                    token.throwIfCancelled();
                    writeChunk(output, node.value.chunk());
                    for (float value : node.value.rawVector()) {
                        output.writeFloat(value);
                    }
                    output.writeInt(node.level());
                    for (int layer = 0; layer <= node.level(); layer++) {
                        int[] neighbors = node.neighborsAt(layer);
                        output.writeInt(neighbors.length);
                        for (int neighbor : neighbors) {
                            output.writeInt(neighbor);
                        }
                    }
                }
                output.flush();
                file.getChannel().force(true);
            }
            token.throwIfCancelled();
            try {
                Files.move(temporary, snapshotFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                // Replacing the live snapshot non-atomically could corrupt the only valid index
                // after a power loss. Preserve it and report the unsupported filesystem instead.
                throw new IOException(
                    "The local filesystem cannot atomically activate the HNSW snapshot; the previous index remains active.",
                    error);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private ReadResult readSnapshot(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file.toFile())))) {
            byte[] magic = readExactly(input, MAGIC.length);
            if (!java.util.Arrays.equals(MAGIC, magic)) {
                throw new IncompatibleIndexException("Unsupported local RAG snapshot format");
            }
            int version = input.readInt();
            return switch (version) {
                case LEGACY_VERSION -> new ReadResult(readLegacySnapshot(input), true);
                case VERSION -> new ReadResult(readHierarchicalSnapshot(input), false);
                default -> throw new IncompatibleIndexException(
                    "Unsupported local RAG snapshot version: " + version);
            };
        } catch (EOFException error) {
            throw new IOException("Truncated local RAG snapshot", error);
        }
    }

    private Snapshot readLegacySnapshot(DataInputStream input) throws IOException {
        verifyEmbeddingHeader(input);
        int size = readNodeCount(input);
        List<RagEmbeddedChunk> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            RagEmbeddedChunk value = readEmbeddedChunk(input);
            int neighborCount = input.readInt();
            if (neighborCount < 0 || neighborCount > size) {
                throw new IOException("Invalid legacy RAG snapshot neighbor count");
            }
            for (int n = 0; n < neighborCount; n++) {
                int neighbor = input.readInt();
                if (neighbor < 0 || neighbor >= size || neighbor == i) {
                    throw new IOException("Invalid legacy RAG snapshot neighbor index");
                }
            }
            values.add(value);
        }
        return build(values, CancellationToken.NONE);
    }

    private Snapshot readHierarchicalSnapshot(DataInputStream input) throws IOException {
        verifyEmbeddingHeader(input);
        int storedM = input.readInt();
        if (storedM <= 0 || storedM > MAX_SNAPSHOT_M) {
            throw new IOException("Invalid RAG snapshot M value");
        }
        int entryPoint = input.readInt();
        int maxLevel = input.readInt();
        int size = readNodeCount(input);
        List<Node> nodes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            RagEmbeddedChunk value = readEmbeddedChunk(input);
            int level = input.readInt();
            if (level < 0 || level > MAX_LEVEL) {
                throw new IOException("Invalid RAG snapshot node level");
            }
            int[][] neighbors = new int[level + 1][];
            for (int layer = 0; layer <= level; layer++) {
                int neighborCount = input.readInt();
                int maxConnections = layer == 0 ? Math.multiplyExact(storedM, 2) : storedM;
                if (neighborCount < 0 || neighborCount > Math.min(size, maxConnections)) {
                    throw new IOException("Invalid RAG snapshot neighbor count");
                }
                neighbors[layer] = new int[neighborCount];
                for (int n = 0; n < neighborCount; n++) {
                    neighbors[layer][n] = input.readInt();
                }
            }
            nodes.add(new Node(value, neighbors));
        }
        validateGraph(nodes, entryPoint, maxLevel);
        return Snapshot.create(nodes, entryPoint, maxLevel);
    }

    private void verifyEmbeddingHeader(DataInputStream input) throws IOException {
        int storedDimensions = input.readInt();
        String storedModel = readString(input);
        if (storedDimensions != dimensions || !storedModel.equals(embeddingModelId)) {
            throw new IncompatibleIndexException(
                "Embedding model or dimensions changed; rebuild the RAG index");
        }
    }

    private int readNodeCount(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_SNAPSHOT_NODES) {
            throw new IOException("Invalid RAG snapshot node count: " + size);
        }
        return size;
    }

    private RagEmbeddedChunk readEmbeddedChunk(DataInputStream input) throws IOException {
        RagChunk chunk = readChunk(input);
        float[] vector = new float[dimensions];
        for (int d = 0; d < dimensions; d++) {
            vector[d] = input.readFloat();
        }
        return new RagEmbeddedChunk(chunk, vector);
    }

    private static void validateGraph(List<Node> nodes, int entryPoint, int maxLevel) throws IOException {
        if (nodes.isEmpty()) {
            if (entryPoint != -1 || maxLevel != -1) {
                throw new IOException("Invalid empty RAG graph header");
            }
            return;
        }
        if (entryPoint < 0 || entryPoint >= nodes.size()) {
            throw new IOException("Invalid RAG snapshot entry point");
        }
        int actualMaxLevel = nodes.stream().mapToInt(Node::level).max().orElse(-1);
        if (maxLevel != actualMaxLevel || nodes.get(entryPoint).level() != maxLevel) {
            throw new IOException("Invalid RAG snapshot maximum level");
        }
        for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
            Node node = nodes.get(nodeIndex);
            for (int layer = 0; layer <= node.level(); layer++) {
                Set<Integer> unique = new HashSet<>();
                for (int neighbor : node.neighborsAt(layer)) {
                    if (neighbor < 0 || neighbor >= nodes.size() || neighbor == nodeIndex
                        || nodes.get(neighbor).level() < layer || !unique.add(neighbor)) {
                        throw new IOException("Invalid RAG snapshot neighbor index");
                    }
                }
            }
        }
    }

    private void validate(RagEmbeddedChunk value) {
        if (value.rawVector().length != dimensions) {
            throw new IllegalArgumentException(
                "Expected " + dimensions + " embedding dimensions, got " + value.rawVector().length);
        }
    }

    private float[] normalized(float[] source) {
        if (source == null || source.length != dimensions) {
            throw new IllegalArgumentException("Query vector dimension mismatch");
        }
        float[] result = source.clone();
        double norm = 0;
        for (float value : result) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Query vector contains non-finite value");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("Query vector must not be zero");
        }
        double scale = 1 / Math.sqrt(norm);
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) (result[i] * scale);
        }
        return result;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
        }
        return dot;
    }

    private static boolean isBetter(double score, int index, double otherScore, int otherIndex) {
        return score > otherScore || score == otherScore && index < otherIndex;
    }

    private static boolean isBetter(ScoredNode left, ScoredNode right) {
        return isBetter(left.score, left.index, right.score, right.index);
    }

    private static boolean isBetter(ScoredMutableNode left, ScoredMutableNode right) {
        return isBetter(left.score, left.node.index, right.score, right.node.index);
    }

    private static boolean isWorse(ScoredNode left, ScoredNode right) {
        return left.score < right.score || left.score == right.score && left.index > right.index;
    }

    private static boolean isWorse(ScoredMutableNode left, ScoredMutableNode right) {
        return left.score < right.score
            || left.score == right.score && left.node.index > right.node.index;
    }

    private static void addBounded(PriorityQueue<ScoredNode> heap, ScoredNode hit, int limit) {
        heap.add(hit);
        if (heap.size() > limit) {
            heap.poll();
        }
    }

    private static void addMutableBounded(
        PriorityQueue<ScoredMutableNode> heap,
        ScoredMutableNode hit,
        int limit
    ) {
        heap.add(hit);
        if (heap.size() > limit) {
            heap.poll();
        }
    }

    private static void writeChunk(DataOutputStream output, RagChunk chunk) throws IOException {
        writeString(output, chunk.id());
        writeString(output, chunk.sourceId());
        writeString(output, chunk.documentPath());
        writeString(output, chunk.documentHash());
        output.writeInt(chunk.chunkIndex());
        output.writeInt(chunk.startOffset());
        output.writeInt(chunk.endOffset());
        writeString(output, chunk.text());
        output.writeInt(chunk.metadata().size());
        for (Map.Entry<String, String> entry : chunk.metadata().entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static RagChunk readChunk(DataInputStream input) throws IOException {
        String id = readString(input);
        String sourceId = readString(input);
        String documentPath = readString(input);
        String documentHash = readString(input);
        int chunkIndex = input.readInt();
        int start = input.readInt();
        int end = input.readInt();
        String text = readString(input);
        int metadataSize = input.readInt();
        if (metadataSize < 0 || metadataSize > 1_000) {
            throw new IOException("Invalid RAG metadata count");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 0; i < metadataSize; i++) {
            metadata.put(readString(input), readString(input));
        }
        return new RagChunk(id, sourceId, documentPath, documentHash, chunkIndex, start, end, text, metadata);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid RAG snapshot string length");
        }
        return new String(readExactly(input, length), StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of RAG snapshot");
        }
        return bytes;
    }

    private static final Comparator<ScoredNode> SCORED_BEST_FIRST = Comparator
        .comparingDouble(ScoredNode::score).reversed().thenComparingInt(ScoredNode::index);
    private static final Comparator<ScoredNode> SCORED_WORST_FIRST = Comparator
        .comparingDouble(ScoredNode::score).thenComparing(ScoredNode::index, Comparator.reverseOrder());
    private static final Comparator<ScoredMutableNode> MUTABLE_BEST_FIRST = Comparator
        .comparingDouble(ScoredMutableNode::score).reversed()
        .thenComparingInt(hit -> hit.node.index);
    private static final Comparator<ScoredMutableNode> MUTABLE_WORST_FIRST = Comparator
        .comparingDouble(ScoredMutableNode::score)
        .thenComparing((ScoredMutableNode hit) -> hit.node.index, Comparator.reverseOrder());

    private record ReadResult(Snapshot snapshot, boolean migrated) { }
    private record SearchOutcome(List<ScoredNode> hits, int visitedCount) { }
    private record ScoredNode(int index, Node node, double score) { }
    private record ScoredMutableNode(MutableNode node, double score) { }

    private record Node(RagEmbeddedChunk value, int[][] neighbors) {
        int level() {
            return neighbors.length - 1;
        }

        int[] neighborsAt(int layer) {
            return layer <= level() ? neighbors[layer] : new int[0];
        }
    }

    private record Snapshot(
        List<Node> nodes,
        int entryPoint,
        int maxLevel,
        Map<String, Integer> sourceEntryPoints
    ) {
        static Snapshot empty() {
            return new Snapshot(List.of(), -1, -1, Map.of());
        }

        static Snapshot create(List<Node> sourceNodes, int entryPoint, int maxLevel) {
            List<Node> nodes = List.copyOf(sourceNodes);
            Map<String, Integer> sourceEntries = new HashMap<>();
            for (int index = 0; index < nodes.size(); index++) {
                String sourceId = nodes.get(index).value.chunk().sourceId();
                Integer previous = sourceEntries.get(sourceId);
                if (previous == null || nodes.get(index).level() > nodes.get(previous).level()) {
                    sourceEntries.put(sourceId, index);
                }
            }
            return new Snapshot(nodes, entryPoint, maxLevel, Map.copyOf(sourceEntries));
        }
    }

    private static final class MutableNode {
        private final int index;
        private final RagEmbeddedChunk value;
        private final int level;
        private final List<List<Integer>> neighbors;

        private MutableNode(int index, RagEmbeddedChunk value, int level) {
            this.index = index;
            this.value = value;
            this.level = level;
            this.neighbors = new ArrayList<>(level + 1);
            for (int layer = 0; layer <= level; layer++) {
                neighbors.add(new ArrayList<>());
            }
        }

        private List<Integer> neighborsAt(int layer) {
            return layer <= level ? neighbors.get(layer) : List.of();
        }

        private void addNeighbor(int layer, int neighborIndex) {
            List<Integer> layerNeighbors = neighbors.get(layer);
            if (!layerNeighbors.contains(neighborIndex)) {
                layerNeighbors.add(neighborIndex);
            }
        }

        private Node freeze() {
            int[][] frozen = new int[level + 1][];
            for (int layer = 0; layer <= level; layer++) {
                frozen[layer] = neighbors.get(layer).stream().mapToInt(Integer::intValue).toArray();
            }
            return new Node(value, frozen);
        }
    }

    public static final class IncompatibleIndexException extends IOException {
        public IncompatibleIndexException(String message) {
            super(message);
        }
    }
}
