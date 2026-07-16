package de.kortty.ai.llama;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, atomically persisted registry of local GGUF models.
 */
public final class LlamaModelRegistry {

    public static final String REGISTRY_FILE_NAME = "models.xml";
    private static final int SCHEMA_VERSION = 1;
    private static final Map<Path, Object> JVM_FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path registryFile;
    private final Path lockFile;
    private final Map<String, LlamaModel> models = new LinkedHashMap<>();

    public LlamaModelRegistry(Path registryFile) {
        if (registryFile == null) {
            throw new IllegalArgumentException("Registry file must be configured.");
        }
        this.registryFile = registryFile.toAbsolutePath().normalize();
        this.lockFile = this.registryFile.resolveSibling(this.registryFile.getFileName() + ".lock");
        reload();
    }

    public static LlamaModelRegistry inDirectory(Path llamaDirectory) {
        if (llamaDirectory == null) {
            throw new IllegalArgumentException("llama.cpp data directory must be configured.");
        }
        return new LlamaModelRegistry(llamaDirectory.resolve(REGISTRY_FILE_NAME));
    }

    public synchronized Path getRegistryFile() {
        return registryFile;
    }

    public synchronized List<LlamaModel> list() {
        return models.values().stream().map(LlamaModel::new).toList();
    }

    public synchronized Optional<LlamaModel> find(String id) {
        String normalizedId = normalizeId(id);
        LlamaModel model = normalizedId != null ? models.get(normalizedId) : null;
        return model != null ? Optional.of(new LlamaModel(model)) : Optional.empty();
    }

    /** Adds or replaces one registry entry and commits the complete registry atomically. */
    public synchronized void register(LlamaModel model) {
        LlamaModel validated = validatedCopy(model);
        mutateCatalog(() -> {
            models.put(validated.getId(), validated);
            return null;
        });
    }

    /** Removes an entry without deleting its GGUF file or installed runtime pack. */
    public synchronized Optional<LlamaModel> remove(String id) {
        String normalizedId = normalizeId(id);
        return mutateCatalog(() -> {
            LlamaModel removed = normalizedId != null ? models.remove(normalizedId) : null;
            return removed != null ? Optional.of(new LlamaModel(removed)) : Optional.empty();
        });
    }

    /**
     * Rebinds every registered model to one verified active runtime in a single atomic registry
     * commit. Model files and all per-model inference settings remain unchanged.
     */
    public synchronized Map<String, Path> replaceServerExecutableForAll(Path serverExecutable) {
        if (serverExecutable == null) {
            throw new IllegalArgumentException("Active llama-server executable must be configured.");
        }
        return mutateCatalog(() -> {
            Map<String, LlamaModel> replacements = new LinkedHashMap<>();
            Map<String, Path> previousBindings = new LinkedHashMap<>();
            for (LlamaModel model : models.values()) {
                previousBindings.put(model.getId(), model.getServerExecutable());
                LlamaModel replacement = withServerExecutable(model, serverExecutable);
                replacements.put(replacement.getId(), replacement);
            }
            models.clear();
            models.putAll(replacements);
            return Map.copyOf(previousBindings);
        });
    }

    /**
     * Restores bindings captured by {@link #replaceServerExecutableForAll(Path)} without replacing
     * models added or edited by another registry instance after that rebind.
     */
    public synchronized void restoreServerExecutables(
        Map<String, Path> previousBindings,
        Path expectedCurrentExecutable
    ) {
        if (previousBindings == null || expectedCurrentExecutable == null) {
            throw new IllegalArgumentException("Previous and current llama-server bindings are required.");
        }
        Map<String, Path> normalizedBindings = new LinkedHashMap<>();
        previousBindings.forEach((id, path) -> {
            String normalizedId = normalizeId(id);
            if (normalizedId == null || path == null) {
                throw new IllegalArgumentException("Previous llama-server bindings contain an invalid entry.");
            }
            normalizedBindings.put(normalizedId, path.toAbsolutePath().normalize());
        });
        Path expected = expectedCurrentExecutable.toAbsolutePath().normalize();
        mutateCatalog(() -> {
            Map<String, LlamaModel> restored = new LinkedHashMap<>();
            for (LlamaModel model : models.values()) {
                Path previous = normalizedBindings.get(model.getId());
                LlamaModel replacement = previous != null
                    && model.getServerExecutable().toAbsolutePath().normalize().equals(expected)
                        ? withServerExecutable(model, previous)
                        : new LlamaModel(model);
                restored.put(replacement.getId(), replacement);
            }
            models.clear();
            models.putAll(restored);
            return null;
        });
    }

    /**
     * Rebinds only models that still point at one specific runtime. This is used to fail closed
     * when a signed index revokes the active package without changing unrelated executable paths.
     */
    public synchronized void replaceServerExecutable(Path expectedExecutable, Path replacementExecutable) {
        if (expectedExecutable == null || replacementExecutable == null) {
            throw new IllegalArgumentException("Expected and replacement llama-server executables are required.");
        }
        Path expected = expectedExecutable.toAbsolutePath().normalize();
        mutateCatalog(() -> {
            Map<String, LlamaModel> replacements = new LinkedHashMap<>();
            for (LlamaModel model : models.values()) {
                LlamaModel replacement = model.getServerExecutable().toAbsolutePath().normalize().equals(expected)
                    ? withServerExecutable(model, replacementExecutable)
                    : new LlamaModel(model);
                replacements.put(replacement.getId(), replacement);
            }
            models.clear();
            models.putAll(replacements);
            return null;
        });
    }

    /** Replaces the complete catalog in one atomic commit. */
    public synchronized void replaceAll(List<LlamaModel> replacements) {
        if (replacements == null) {
            throw new IllegalArgumentException("Replacement model catalog must be configured.");
        }
        Map<String, LlamaModel> validated = new LinkedHashMap<>();
        for (LlamaModel model : replacements) {
            LlamaModel copy = validatedCopy(model);
            if (validated.putIfAbsent(copy.getId(), copy) != null) {
                throw new IllegalArgumentException("Duplicate local model id: " + copy.getId());
            }
        }
        mutateCatalog(() -> {
            models.clear();
            models.putAll(validated);
            return null;
        });
    }

    public synchronized void reload() {
        withCatalogLock(() -> {
            reloadUnlocked();
            return null;
        });
    }

    private void reloadUnlocked() {
        if (!Files.exists(registryFile)) {
            models.clear();
            return;
        }
        try {
            Map<String, LlamaModel> loadedModels = new LinkedHashMap<>();
            JAXBContext context = JAXBContext.newInstance(Catalog.class, LlamaModel.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            XMLInputFactory inputFactory = XMLInputFactory.newFactory();
            inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            inputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            Catalog catalog;
            try (InputStream input = Files.newInputStream(registryFile)) {
                XMLStreamReader reader = inputFactory.createXMLStreamReader(input);
                try {
                    catalog = (Catalog) unmarshaller.unmarshal(reader);
                } finally {
                    reader.close();
                }
            }
            if (catalog.schemaVersion > SCHEMA_VERSION) {
                throw new LlamaRegistryException(
                    "Local model registry schema " + catalog.schemaVersion + " is newer than this korTTY version supports.");
            }
            for (LlamaModel model : catalog.models) {
                LlamaModel validated = validatedCopy(model);
                if (loadedModels.putIfAbsent(validated.getId(), validated) != null) {
                    throw new LlamaRegistryException("Duplicate local model id in registry: " + validated.getId());
                }
            }
            models.clear();
            models.putAll(loadedModels);
        } catch (LlamaRegistryException e) {
            throw e;
        } catch (Exception e) {
            throw new LlamaRegistryException("Could not read local model registry " + registryFile + ".", e);
        }
    }

    private void saveUnlocked() {
        Path parent = registryFile.getParent();
        Path tempFile = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String prefix = registryFile.getFileName().toString();
            tempFile = Files.createTempFile(parent, prefix, ".tmp");
            JAXBContext context = JAXBContext.newInstance(Catalog.class, LlamaModel.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            Catalog catalog = new Catalog();
            catalog.models = new ArrayList<>(models.values());
            try (OutputStream output = Files.newOutputStream(tempFile)) {
                marshaller.marshal(catalog, output);
            }
            try {
                Files.move(
                    tempFile,
                    registryFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new LlamaRegistryException("Could not write local model registry " + registryFile + ".", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup; the committed registry is unaffected.
                }
            }
        }
    }

    /** Caller holds this instance monitor; the shared lock closes the cross-instance race. */
    private <T> T mutateCatalog(CatalogMutation<T> mutation) {
        return withCatalogLock(() -> {
            reloadUnlocked();
            Map<String, LlamaModel> previous = copyModels(models);
            try {
                T result = mutation.apply();
                saveUnlocked();
                return result;
            } catch (RuntimeException e) {
                models.clear();
                models.putAll(previous);
                throw e;
            }
        });
    }

    private <T> T withCatalogLock(LockedOperation<T> operation) {
        Object jvmLock = JVM_FILE_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
        synchronized (jvmLock) {
            try {
                Path parent = lockFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileChannel channel = FileChannel.open(
                        lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    return operation.run();
                }
            } catch (LlamaRegistryException e) {
                throw e;
            } catch (Exception e) {
                throw new LlamaRegistryException("Could not lock local model registry " + registryFile + ".", e);
            }
        }
    }

    private static Map<String, LlamaModel> copyModels(Map<String, LlamaModel> source) {
        Map<String, LlamaModel> copy = new LinkedHashMap<>();
        source.forEach((id, model) -> copy.put(id, new LlamaModel(model)));
        return copy;
    }

    private static LlamaModel withServerExecutable(LlamaModel model, Path serverExecutable) {
        return new LlamaModel(
            model.getId(), model.getDisplayName(), model.getModelPath(), serverExecutable,
            model.getBackend(), model.getPurpose(), model.getContextSize(), model.getThreadCount(),
            model.getGpuLayers(), model.getIdleTimeoutMinutes());
    }

    @FunctionalInterface
    private interface CatalogMutation<T> {
        T apply();
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws Exception;
    }

    private static LlamaModel validatedCopy(LlamaModel model) {
        if (model == null) {
            throw new IllegalArgumentException("Local model must not be null.");
        }
        LlamaModel copy = new LlamaModel(model);
        copy.validate();
        return copy;
    }

    private static String normalizeId(String id) {
        return id != null && !id.isBlank() ? id.trim() : null;
    }

    @XmlRootElement(name = "llamaModels")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static final class Catalog {

        @XmlElement
        private int schemaVersion = SCHEMA_VERSION;

        @XmlElement(name = "model")
        private List<LlamaModel> models = new ArrayList<>();

        public Catalog() {
        }
    }
}
