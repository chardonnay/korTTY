package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

@XmlRootElement(name = "action")
@XmlAccessorType(XmlAccessType.FIELD)
public class JobAction {

    @XmlElement
    private JobActionType type = JobActionType.COMMAND;

    @XmlElement
    private String command;

    @XmlElement
    private String snippetId;

    @XmlElementWrapper(name = "snippetArguments")
    @XmlElement(name = "argument")
    private List<String> snippetArguments = new ArrayList<>();

    @XmlElement
    private String aiPrompt;

    @XmlElement
    private String aiProfileId;

    @XmlElement
    private boolean aiAutoApproveCommands;

    @XmlElement
    private Integer swarmMaxParallelism;

    @XmlElement
    private boolean swarmReadOnly = true;

    @XmlElement
    private String localPath;

    @XmlElement
    private String remotePath;

    @XmlElement
    private String remoteSourcePath;

    @XmlElement
    private String remoteDestinationPath;

    @XmlElement
    private String newName;

    @XmlElement
    private String permissions;

    @XmlElement
    private String owner;

    @XmlElement
    private String group;

    @XmlElement
    private SftpSyncDirection syncDirection = SftpSyncDirection.UPLOAD;

    @XmlElement
    private boolean useSudo;

    @XmlElement
    private boolean sudoStagingEnabled;

    @XmlElementWrapper(name = "archiveSources")
    @XmlElement(name = "path")
    private List<String> archiveSourcePaths = new ArrayList<>();

    @XmlElementWrapper(name = "archiveExcludes")
    @XmlElement(name = "pattern")
    private List<String> archiveExcludePatterns = new ArrayList<>();

    @XmlElement
    private String archivePath;

    @XmlElement
    private JobArchiveFormat archiveFormat = JobArchiveFormat.ZIP;

    @XmlElement
    private int archiveCompressionLevel = 6;

    @XmlElement
    private boolean archiveDownloadAfterCreate;

    @XmlElement
    private String archiveDownloadLocalPath;

    @XmlElement
    private String encryptedArchivePassword;

    @XmlElement
    private RsyncDirection rsyncDirection = RsyncDirection.UPLOAD;

    @XmlElementWrapper(name = "rsyncSources")
    @XmlElement(name = "path")
    private List<String> rsyncSourcePaths = new ArrayList<>();

    @XmlElement
    private String rsyncTargetRoot;

    @XmlElement
    private boolean rsyncDeleteEnabled;

    public JobActionType getType() {
        return type != null ? type : JobActionType.COMMAND;
    }

    public void setType(JobActionType type) {
        this.type = type != null ? type : JobActionType.COMMAND;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = trimToNull(command);
    }

    public String getSnippetId() {
        return snippetId;
    }

    public void setSnippetId(String snippetId) {
        this.snippetId = trimToNull(snippetId);
    }

    public List<String> getSnippetArguments() {
        if (snippetArguments == null) {
            snippetArguments = new ArrayList<>();
        }
        return snippetArguments;
    }

    public void setSnippetArguments(List<String> snippetArguments) {
        this.snippetArguments = snippetArguments != null ? new ArrayList<>(snippetArguments) : new ArrayList<>();
    }

    public String getAiPrompt() {
        return aiPrompt;
    }

    public void setAiPrompt(String aiPrompt) {
        this.aiPrompt = trimToNull(aiPrompt);
    }

    public String getAiProfileId() {
        return aiProfileId;
    }

    public void setAiProfileId(String aiProfileId) {
        this.aiProfileId = trimToNull(aiProfileId);
    }

    public boolean isAiAutoApproveCommands() {
        return aiAutoApproveCommands;
    }

    public void setAiAutoApproveCommands(boolean aiAutoApproveCommands) {
        this.aiAutoApproveCommands = aiAutoApproveCommands;
    }

    public Integer getSwarmMaxParallelism() {
        return swarmMaxParallelism;
    }

    public void setSwarmMaxParallelism(Integer swarmMaxParallelism) {
        this.swarmMaxParallelism = swarmMaxParallelism;
    }

    /** Effective swarm parallelism: persisted value clamped to 1..16, default 4. */
    public int effectiveSwarmParallelism() {
        int value = swarmMaxParallelism != null ? swarmMaxParallelism : 4;
        return Math.max(1, Math.min(16, value));
    }

    public boolean isSwarmReadOnly() {
        return swarmReadOnly;
    }

    public void setSwarmReadOnly(boolean swarmReadOnly) {
        this.swarmReadOnly = swarmReadOnly;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = trimToNull(localPath);
    }

    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(String remotePath) {
        this.remotePath = trimToNull(remotePath);
    }

    public String getRemoteSourcePath() {
        return remoteSourcePath;
    }

    public void setRemoteSourcePath(String remoteSourcePath) {
        this.remoteSourcePath = trimToNull(remoteSourcePath);
    }

    public String getRemoteDestinationPath() {
        return remoteDestinationPath;
    }

    public void setRemoteDestinationPath(String remoteDestinationPath) {
        this.remoteDestinationPath = trimToNull(remoteDestinationPath);
    }

    public String getNewName() {
        return newName;
    }

    public void setNewName(String newName) {
        this.newName = trimToNull(newName);
    }

    public String getPermissions() {
        return permissions;
    }

    public void setPermissions(String permissions) {
        this.permissions = trimToNull(permissions);
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = trimToNull(owner);
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = trimToNull(group);
    }

    public SftpSyncDirection getSyncDirection() {
        return syncDirection != null ? syncDirection : SftpSyncDirection.UPLOAD;
    }

    public void setSyncDirection(SftpSyncDirection syncDirection) {
        this.syncDirection = syncDirection != null ? syncDirection : SftpSyncDirection.UPLOAD;
    }

    public boolean isUseSudo() {
        return useSudo;
    }

    public void setUseSudo(boolean useSudo) {
        this.useSudo = useSudo;
    }

    public boolean isSudoStagingEnabled() {
        return sudoStagingEnabled;
    }

    public void setSudoStagingEnabled(boolean sudoStagingEnabled) {
        this.sudoStagingEnabled = sudoStagingEnabled;
    }

    public List<String> getArchiveSourcePaths() {
        if (archiveSourcePaths == null) {
            archiveSourcePaths = new ArrayList<>();
        }
        return archiveSourcePaths;
    }

    public void setArchiveSourcePaths(List<String> archiveSourcePaths) {
        this.archiveSourcePaths = archiveSourcePaths != null ? new ArrayList<>(archiveSourcePaths) : new ArrayList<>();
    }

    public List<String> getArchiveExcludePatterns() {
        if (archiveExcludePatterns == null) {
            archiveExcludePatterns = new ArrayList<>();
        }
        return archiveExcludePatterns;
    }

    public void setArchiveExcludePatterns(List<String> archiveExcludePatterns) {
        this.archiveExcludePatterns = archiveExcludePatterns != null ? new ArrayList<>(archiveExcludePatterns) : new ArrayList<>();
    }

    public String getArchivePath() {
        return archivePath;
    }

    public void setArchivePath(String archivePath) {
        this.archivePath = trimToNull(archivePath);
    }

    public JobArchiveFormat getArchiveFormat() {
        return archiveFormat != null ? archiveFormat : JobArchiveFormat.ZIP;
    }

    public void setArchiveFormat(JobArchiveFormat archiveFormat) {
        this.archiveFormat = archiveFormat != null ? archiveFormat : JobArchiveFormat.ZIP;
    }

    public int getArchiveCompressionLevel() {
        return Math.max(0, Math.min(9, archiveCompressionLevel));
    }

    public void setArchiveCompressionLevel(int archiveCompressionLevel) {
        this.archiveCompressionLevel = Math.max(0, Math.min(9, archiveCompressionLevel));
    }

    public boolean isArchiveDownloadAfterCreate() {
        return archiveDownloadAfterCreate;
    }

    public void setArchiveDownloadAfterCreate(boolean archiveDownloadAfterCreate) {
        this.archiveDownloadAfterCreate = archiveDownloadAfterCreate;
    }

    public String getArchiveDownloadLocalPath() {
        return archiveDownloadLocalPath;
    }

    public void setArchiveDownloadLocalPath(String archiveDownloadLocalPath) {
        this.archiveDownloadLocalPath = trimToNull(archiveDownloadLocalPath);
    }

    public String getEncryptedArchivePassword() {
        return encryptedArchivePassword;
    }

    public void setEncryptedArchivePassword(String encryptedArchivePassword) {
        this.encryptedArchivePassword = trimToNull(encryptedArchivePassword);
    }

    public RsyncDirection getRsyncDirection() {
        return rsyncDirection != null ? rsyncDirection : RsyncDirection.UPLOAD;
    }

    public void setRsyncDirection(RsyncDirection rsyncDirection) {
        this.rsyncDirection = rsyncDirection != null ? rsyncDirection : RsyncDirection.UPLOAD;
    }

    public List<String> getRsyncSourcePaths() {
        if (rsyncSourcePaths == null) {
            rsyncSourcePaths = new ArrayList<>();
        }
        return rsyncSourcePaths;
    }

    public void setRsyncSourcePaths(List<String> rsyncSourcePaths) {
        this.rsyncSourcePaths = rsyncSourcePaths != null ? new ArrayList<>(rsyncSourcePaths) : new ArrayList<>();
    }

    public String getRsyncTargetRoot() {
        return rsyncTargetRoot;
    }

    public void setRsyncTargetRoot(String rsyncTargetRoot) {
        this.rsyncTargetRoot = trimToNull(rsyncTargetRoot);
    }

    public boolean isRsyncDeleteEnabled() {
        return rsyncDeleteEnabled;
    }

    public void setRsyncDeleteEnabled(boolean rsyncDeleteEnabled) {
        this.rsyncDeleteEnabled = rsyncDeleteEnabled;
    }

    private String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }
}
