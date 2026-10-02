package de.kortty.core;

import de.kortty.model.SnippetVariable;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Manages custom snippet variables and their stored values.
 */
public class SnippetVariableManager {

    private static final Logger logger = LoggerFactory.getLogger(SnippetVariableManager.class);
    public static final String VARIABLES_FILE = "snippet-variables.xml";

    /** Shared, thread-safe JAXBContext; a Marshaller/Unmarshaller is created per call. */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(VariablesWrapper.class, SnippetVariable.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Path configDir;
    private final List<SnippetVariable> variables = new ArrayList<>();
    /** Where an unreadable variables file was moved aside during the last {@link #load()}, if any. */
    private Path loadFailureBackup;
    /** Set when the file was unreadable AND could not be moved aside: saving would destroy it. */
    private boolean saveBlockedByUnreadableFile;

    public SnippetVariableManager(Path configDir) {
        this.configDir = configDir;
    }

    /**
     * Loads the variables file. An unreadable file is moved aside (see {@link #getLoadFailureBackup()})
     * and the manager continues empty, so the next save creates a fresh file instead of overwriting
     * the user's data. Only when the file can neither be parsed nor moved aside does this throw — and
     * then {@link #save()} refuses to run.
     */
    public void load() throws Exception {
        Path file = configDir.resolve(VARIABLES_FILE);
        loadFailureBackup = null;
        saveBlockedByUnreadableFile = false;
        if (!Files.exists(file)) {
            logger.info("No snippet variables file found, starting empty");
            return;
        }

        VariablesWrapper wrapper;
        try {
            Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
            wrapper = (VariablesWrapper) unmarshaller.unmarshal(file.toFile());
        } catch (Exception parseFailure) {
            try {
                loadFailureBackup = CorruptFileQuarantine.moveAside(file);
            } catch (IOException quarantineFailure) {
                saveBlockedByUnreadableFile = true;
                logger.error("Failed to load snippet variables from {} and could not move the file aside ({}); "
                    + "saving is disabled for this session so the file is not overwritten",
                    file, quarantineFailure.toString(), parseFailure);
                throw parseFailure;
            }
            logger.error("Failed to load snippet variables from {}; the file was moved to {} and korTTY "
                + "continues without stored variables", file, loadFailureBackup, parseFailure);
            variables.clear();
            return;
        }

        variables.clear();
        if (wrapper.getVariables() != null) {
            variables.addAll(wrapper.getVariables());
        }
        logger.info("Loaded {} snippet variables from {}", variables.size(), file);
    }

    /** Writes the variables file atomically (sibling temp file + move). */
    public void save() throws Exception {
        Path file = configDir.resolve(VARIABLES_FILE);
        if (saveBlockedByUnreadableFile) {
            throw new IllegalStateException("Refusing to overwrite the unreadable snippet variables file " + file
                + " — it could not be moved aside during load");
        }
        VariablesWrapper wrapper = new VariablesWrapper();
        wrapper.setVariables(new ArrayList<>(variables));

        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        StringWriter xml = new StringWriter();
        marshaller.marshal(wrapper, xml);

        Files.createDirectories(configDir);
        AtomicFileWriter.writeStringAtomically(file, xml.toString());
        logger.info("Saved {} snippet variables to {}", variables.size(), file);
    }

    /**
     * Path of the {@code snippet-variables.xml.corrupt-<timestamp>} copy the last {@link #load()}
     * moved an unreadable variables file to.
     */
    public Optional<Path> getLoadFailureBackup() {
        return Optional.ofNullable(loadFailureBackup);
    }

    public List<SnippetVariable> getAll() {
        List<SnippetVariable> copy = new ArrayList<>(variables);
        copy.sort(Comparator.comparing(v -> v.getName() != null ? v.getName().toLowerCase() : ""));
        return copy;
    }

    public Optional<SnippetVariable> findByName(String name) {
        if (name == null) return Optional.empty();
        return variables.stream()
                .filter(v -> name.equalsIgnoreCase(v.getName()))
                .findFirst();
    }

    public String getValue(String name) {
        return findByName(name)
                .map(SnippetVariable::getValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(null);
    }

    public void addOrUpdate(String name, String value) {
        if (name == null || name.isBlank()) return;
        Optional<SnippetVariable> existing = findByName(name);
        if (existing.isPresent()) {
            existing.get().setValue(value);
        } else {
            variables.add(new SnippetVariable(name.trim(), value));
        }
    }

    public void remove(String name) {
        if (name == null) return;
        variables.removeIf(v -> name.equalsIgnoreCase(v.getName()));
    }

    @XmlRootElement(name = "snippetVariables")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class VariablesWrapper {

        @XmlElement(name = "variable")
        private List<SnippetVariable> variables;

        public List<SnippetVariable> getVariables() {
            return variables;
        }

        public void setVariables(List<SnippetVariable> variables) {
            this.variables = variables;
        }
    }
}
