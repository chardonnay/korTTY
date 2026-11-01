package de.kortty.memory.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds the memory files of other AI tools in the places they are known to use. It only lists paths: nothing is read
 * until the user approves a file. Every candidate passes {@link ExternalMemoryAccess} before it is listed, so a link that
 * leaves its folder is never offered.
 *
 * <p>The table is fixed. The Claude Code auto-memory slug is not documented, so every project folder under
 * {@code ~/.claude/projects} is listed and the user picks one; Qwen's auto memory is listed the same way.</p>
 */
public final class ExternalMemoryDiscovery {

    /**
     * A file found in a known place.
     *
     * @param path  the file's canonical path
     * @param root  the canonical folder the file was found under: the home folder or the project folder
     * @param tool  the tool the file belongs to
     * @param label what the list shows: the path under its folder, or the project folder name for auto memory
     */
    public record Candidate(Path path, Path root, ExternalMemoryTool tool, String label) {
    }

    private static final Logger logger = LoggerFactory.getLogger(ExternalMemoryDiscovery.class);

    private final ExternalMemoryAccess access;

    public ExternalMemoryDiscovery(ExternalMemoryAccess access) {
        this.access = access;
    }

    /** The known files under the home folder, in table order. */
    public List<Candidate> homeCandidates() {
        Path home = access.home();
        List<Candidate> found = new ArrayList<>();
        addFile(found, home, home.resolve(".claude/CLAUDE.md"), ExternalMemoryTool.CLAUDE_CODE);
        addFiles(found, home, listFiles(home.resolve(".claude/rules"), ".md"), ExternalMemoryTool.CLAUDE_CODE);
        addAutoMemory(found, home, home.resolve(".claude/projects"), ExternalMemoryTool.CLAUDE_CODE);
        addFile(found, home, home.resolve(".codex/AGENTS.md"), ExternalMemoryTool.CODEX);
        addFile(found, home, home.resolve(".qwen/QWEN.md"), ExternalMemoryTool.QWEN);
        addAutoMemory(found, home, home.resolve(".qwen/projects"), ExternalMemoryTool.QWEN);
        addFile(found, home, home.resolve(".config/opencode/AGENTS.md"), ExternalMemoryTool.OPENCODE);
        addFile(found, home, home.resolve(".config/kilo/AGENTS.md"), ExternalMemoryTool.KILO);
        addFiles(found, home, listFiles(home.resolve(".kiro/steering"), ".md"), ExternalMemoryTool.KIRO);
        addFile(found, home, home.resolve(".gemini/GEMINI.md"), ExternalMemoryTool.GEMINI);
        addFiles(found, home, listFiles(home.resolve("Documents/Cline/Rules"), ".md"), ExternalMemoryTool.CLINE);
        return found;
    }

    /** The known files inside a project folder, in table order. The folder is the root every candidate must stay in. */
    public List<Candidate> projectCandidates(Path project) {
        Path root = project.toAbsolutePath().normalize();
        List<Candidate> found = new ArrayList<>();
        addFile(found, root, root.resolve("CLAUDE.md"), ExternalMemoryTool.CLAUDE_CODE);
        addFile(found, root, root.resolve("AGENTS.md"), ExternalMemoryTool.AGENTS_MD);
        addFile(found, root, root.resolve(".github/copilot-instructions.md"), ExternalMemoryTool.COPILOT);
        addFiles(found, root, listFiles(root.resolve(".clinerules"), ".md"), ExternalMemoryTool.CLINE);
        addFiles(found, root, listFiles(root.resolve(".roo/rules"), ".md"), ExternalMemoryTool.ROO);
        addFiles(found, root, listFiles(root.resolve(".cursor/rules"), ".mdc"), ExternalMemoryTool.CURSOR);
        return found;
    }

    private void addFile(List<Candidate> found, Path root, Path file, ExternalMemoryTool tool) {
        if (Files.isRegularFile(file)) {
            addFiles(found, root, List.of(file), tool);
        }
    }

    private void addFiles(List<Candidate> found, Path root, List<Path> files, ExternalMemoryTool tool) {
        for (Path file : files) {
            add(found, root, file, tool, labelOf(root, file));
        }
    }

    /** The {@code memory/MEMORY.md} file of every project folder below {@code projects}, labelled with the folder name. */
    private void addAutoMemory(List<Candidate> found, Path root, Path projects, ExternalMemoryTool tool) {
        for (Path project : subdirectories(projects)) {
            Path file = project.resolve("memory").resolve("MEMORY.md");
            if (Files.isRegularFile(file)) {
                add(found, root, file, tool, project.getFileName().toString());
            }
        }
    }

    private void add(List<Candidate> found, Path root, Path file, ExternalMemoryTool tool, String label) {
        ExternalMemoryAccess.Outcome outcome = access.check(file, root);
        if (outcome.allowed()) {
            found.add(new Candidate(outcome.file(), outcome.root(), tool, label));
        } else {
            logger.debug("A known memory file is left out: {}", outcome.refusal().orElse(null));
        }
    }

    private String labelOf(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return root.equals(access.home()) ? "~/" + relative : relative;
    }

    private static List<Path> listFiles(Path directory, String suffix) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children
                .filter(child -> child.getFileName() != null && child.getFileName().toString().endsWith(suffix))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(child -> child.getFileName().toString()))
                .toList();
        } catch (IOException | RuntimeException e) {
            logger.debug("A memory folder could not be listed");
            return List.of();
        }
    }

    private static List<Path> subdirectories(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(child -> child.getFileName().toString()))
                .toList();
        } catch (IOException | RuntimeException e) {
            logger.debug("A memory folder could not be listed");
            return List.of();
        }
    }
}
