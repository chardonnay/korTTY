package de.kortty.core;

import de.kortty.model.SnippetVariable;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final String VARIABLES_FILE = "snippet-variables.xml";

    private final Path configDir;
    private final List<SnippetVariable> variables = new ArrayList<>();

    public SnippetVariableManager(Path configDir) {
        this.configDir = configDir;
    }

    public void load() throws Exception {
        Path file = configDir.resolve(VARIABLES_FILE);
        if (!Files.exists(file)) {
            logger.info("No snippet variables file found, starting empty");
            return;
        }

        JAXBContext context = JAXBContext.newInstance(VariablesWrapper.class, SnippetVariable.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        VariablesWrapper wrapper = (VariablesWrapper) unmarshaller.unmarshal(file.toFile());

        variables.clear();
        if (wrapper.getVariables() != null) {
            variables.addAll(wrapper.getVariables());
        }
        logger.info("Loaded {} snippet variables from {}", variables.size(), file);
    }

    public void save() throws Exception {
        Path file = configDir.resolve(VARIABLES_FILE);
        VariablesWrapper wrapper = new VariablesWrapper();
        wrapper.setVariables(new ArrayList<>(variables));

        JAXBContext context = JAXBContext.newInstance(VariablesWrapper.class, SnippetVariable.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);

        Files.createDirectories(configDir);
        marshaller.marshal(wrapper, file.toFile());
        logger.info("Saved {} snippet variables to {}", variables.size(), file);
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
