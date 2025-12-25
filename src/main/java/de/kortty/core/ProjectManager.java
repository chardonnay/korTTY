package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;
import de.kortty.persistence.HistoryStorage;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Manages project saving and loading including terminal histories.
 */
public class ProjectManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ProjectManager.class);
    private static final String PROJECT_EXTENSION = ".kortty";
    
    private final Path projectsDir;
    private final HistoryStorage historyStorage;
    private Project currentProject;
    
    public ProjectManager(Path configDir) {
        this.projectsDir = configDir.resolve("projects");
        this.historyStorage = new HistoryStorage(configDir);
        
        try {
            if (!Files.exists(projectsDir)) {
                Files.createDirectories(projectsDir);
            }
        } catch (IOException e) {
            logger.error("Failed to create projects directory", e);
        }
    }
    
    /**
     * Creates a new project.
     */
    public Project createProject(String name) {
        Project project = new Project(name);
        this.currentProject = project;
        return project;
    }
    
    /**
     * Saves the current project to disk.
     */
    public void saveProject(Project project, Path filePath) throws Exception {
        project.setLastModified(LocalDateTime.now());
        project.setProjectFilePath(filePath.toString());
        
        // Save terminal histories to separate files
        for (WindowState window : project.getWindows()) {
            for (SessionState session : window.getTabs()) {
                if (session.getTerminalHistory() != null) {
                    String historyFile = historyStorage.saveHistory(
                            session.getSessionId(), 
                            session.getTerminalHistory()
                    );
                    session.setHistoryFilePath(historyFile);
                    session.setTerminalHistory(null); // Don't store in XML
                }
            }
        }
        
        // Save project XML
        JAXBContext context = JAXBContext.newInstance(Project.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        
        try (OutputStream out = Files.newOutputStream(filePath)) {
            marshaller.marshal(project, out);
        }
        
        this.currentProject = project;
        logger.info("Project saved: {}", filePath);
    }
    
    /**
     * Loads a project from disk.
     */
    public Project loadProject(Path filePath) throws Exception {
        JAXBContext context = JAXBContext.newInstance(Project.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        
        Project project;
        try (InputStream in = Files.newInputStream(filePath)) {
            project = (Project) unmarshaller.unmarshal(in);
        }
        
        // Load terminal histories
        for (WindowState window : project.getWindows()) {
            for (SessionState session : window.getTabs()) {
                if (session.getHistoryFilePath() != null) {
                    String history = historyStorage.loadHistory(session.getHistoryFilePath());
                    session.setTerminalHistory(history);
                }
            }
        }
        
        project.setProjectFilePath(filePath.toString());
        this.currentProject = project;
        logger.info("Project loaded: {}", filePath);
        
        return project;
    }
    
    /**
     * Saves project to its existing file path.
     */
    public void saveProject() throws Exception {
        if (currentProject == null) {
            throw new IllegalStateException("No project is currently open");
        }
        
        String filePath = currentProject.getProjectFilePath();
        if (filePath == null) {
            throw new IllegalStateException("Project has no file path");
        }
        
        saveProject(currentProject, Path.of(filePath));
    }
    
    /**
     * Exports project to a specified location.
     */
    public void exportProject(Project project, Path targetPath) throws Exception {
        saveProject(project, targetPath);
    }
    
    /**
     * Lists available projects in the projects directory.
     */
    public java.util.List<Path> listProjects() throws IOException {
        if (!Files.exists(projectsDir)) {
            return java.util.Collections.emptyList();
        }
        
        try (var stream = Files.list(projectsDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(PROJECT_EXTENSION))
                    .toList();
        }
    }
    
    /**
     * Deletes a project.
     */
    public void deleteProject(Path projectPath) throws IOException {
        // Load project to get history files
        try {
            Project project = loadProject(projectPath);
            for (WindowState window : project.getWindows()) {
                for (SessionState session : window.getTabs()) {
                    if (session.getHistoryFilePath() != null) {
                        historyStorage.deleteHistory(session.getHistoryFilePath());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not load project to delete histories", e);
        }
        
        Files.deleteIfExists(projectPath);
        logger.info("Project deleted: {}", projectPath);
    }
    
    public Project getCurrentProject() {
        return currentProject;
    }
    
    public void setCurrentProject(Project currentProject) {
        this.currentProject = currentProject;
    }
    
    public Path getProjectsDir() {
        return projectsDir;
    }
    
    public static String getProjectExtension() {
        return PROJECT_EXTENSION;
    }
}
