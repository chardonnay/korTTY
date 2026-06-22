package de.kortty.jobscheduler;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class JobSchedulerRepository {

    public static final String FILE_NAME = "job-scheduler.xml";
    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerRepository.class);

    private final Path file;
    private SchedulerData data = new SchedulerData();

    public JobSchedulerRepository(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
    }

    public synchronized void load() throws Exception {
        if (!Files.exists(file)) {
            data = new SchedulerData();
            return;
        }
        JAXBContext context = jaxbContext();
        Unmarshaller unmarshaller = context.createUnmarshaller();
        try (InputStream in = Files.newInputStream(file)) {
            data = (SchedulerData) unmarshaller.unmarshal(in);
        }
        if (data == null) {
            data = new SchedulerData();
        }
        data.normalize();
        logger.info("Loaded {} scheduled jobs from {}", data.getJobs().size(), file);
    }

    public synchronized void save() throws Exception {
        Files.createDirectories(file.getParent());
        JAXBContext context = jaxbContext();
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        try (OutputStream out = Files.newOutputStream(file)) {
            marshaller.marshal(data, out);
        }
        // The scheduler ticks frequently and saves each time. Only surface this at INFO when there is
        // at least one active (enabled) job; otherwise it just clutters the log ("Saved 0 scheduled
        // jobs ..."). The empty/disabled case stays available at DEBUG for troubleshooting.
        long active = data.getJobs().stream().filter(ScheduledJob::isEnabled).count();
        if (active > 0) {
            logger.info("Saved {} scheduled jobs to {} ({} active)", data.getJobs().size(), file, active);
        } else {
            logger.debug("Saved {} scheduled jobs to {} (no active jobs)", data.getJobs().size(), file);
        }
    }

    public synchronized List<ScheduledJob> getJobs() {
        return new ArrayList<>(data.getJobs());
    }

    public synchronized Optional<ScheduledJob> findJob(String jobId) {
        return data.getJobs().stream()
            .filter(job -> job.getId().equals(jobId))
            .findFirst();
    }

    public synchronized void upsertJob(ScheduledJob job) {
        if (job == null) {
            return;
        }
        List<ScheduledJob> jobs = data.getJobs();
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).getId().equals(job.getId())) {
                jobs.set(i, job);
                return;
            }
        }
        jobs.add(job);
    }

    public synchronized void deleteJob(String jobId) {
        data.getJobs().removeIf(job -> job.getId().equals(jobId));
    }

    public synchronized List<JobJournalEntry> getJournal() {
        return data.getJournal().stream()
            .sorted(Comparator.comparing(this::journalStartedInstant).reversed())
            .toList();
    }

    private Instant journalStartedInstant(JobJournalEntry entry) {
        return parseJournalInstant(entry != null ? entry.getStartedAt() : null).orElse(Instant.MIN);
    }

    public synchronized List<JobJournalEntry> getJournalForJob(String jobId) {
        return getJournal().stream()
            .filter(entry -> jobId != null && jobId.equals(entry.getJobId()))
            .toList();
    }

    public synchronized void appendJournal(JobJournalEntry entry) {
        if (entry != null) {
            data.getJournal().add(entry);
        }
    }

    public synchronized int deleteJournalEntries(Collection<String> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return 0;
        }
        Set<String> idsToDelete = new HashSet<>();
        for (String entryId : entryIds) {
            if (entryId != null && !entryId.isBlank()) {
                idsToDelete.add(entryId);
            }
        }
        if (idsToDelete.isEmpty()) {
            return 0;
        }
        int before = data.getJournal().size();
        data.getJournal().removeIf(entry -> idsToDelete.contains(entry.getId()));
        return before - data.getJournal().size();
    }

    public synchronized int deleteJournalEntriesOlderThan(Instant cutoff) {
        if (cutoff == null) {
            return 0;
        }
        int before = data.getJournal().size();
        data.getJournal().removeIf(entry -> journalRetentionInstant(entry)
            .map(instant -> instant.isBefore(cutoff))
            .orElse(false));
        return before - data.getJournal().size();
    }

    private Optional<Instant> journalRetentionInstant(JobJournalEntry entry) {
        if (entry == null) {
            return Optional.empty();
        }
        Optional<Instant> finishedAt = parseJournalInstant(entry.getFinishedAt());
        return finishedAt.isPresent() ? finishedAt : parseJournalInstant(entry.getStartedAt());
    }

    private Optional<Instant> parseJournalInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(value).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Instant.parse(value));
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    public synchronized List<SudoCredential> getSudoCredentials() {
        return new ArrayList<>(data.getSudoCredentials());
    }

    public synchronized void upsertSudoCredential(SudoCredential credential) {
        if (credential == null) {
            return;
        }
        List<SudoCredential> credentials = data.getSudoCredentials();
        credentials.removeIf(existing -> sameSudoScope(existing, credential));
        credentials.add(credential);
    }

    public synchronized Optional<SudoCredential> findServerSudoCredential(String connectionId) {
        return data.getSudoCredentials().stream()
            .filter(credential -> credential.getScope() == SudoSecretScope.SERVER)
            .filter(credential -> connectionId != null && connectionId.equals(credential.getServerConnectionId()))
            .findFirst();
    }

    public synchronized Optional<SudoCredential> findGroupSudoCredential(String groupName) {
        return data.getSudoCredentials().stream()
            .filter(credential -> credential.getScope() == SudoSecretScope.GROUP)
            .filter(credential -> groupName != null && groupName.equals(credential.getGroupName()))
            .findFirst();
    }

    public synchronized List<PinnedHostKey> getPinnedHostKeys() {
        return new ArrayList<>(data.getPinnedHostKeys());
    }

    public synchronized Optional<PinnedHostKey> findPinnedHostKey(String connectionId) {
        return data.getPinnedHostKeys().stream()
            .filter(hostKey -> connectionId != null && connectionId.equals(hostKey.getConnectionId()))
            .findFirst();
    }

    public synchronized void upsertPinnedHostKey(PinnedHostKey hostKey) {
        if (hostKey == null || hostKey.getConnectionId() == null) {
            return;
        }
        data.getPinnedHostKeys().removeIf(existing -> hostKey.getConnectionId().equals(existing.getConnectionId()));
        data.getPinnedHostKeys().add(hostKey);
    }

    private boolean sameSudoScope(SudoCredential left, SudoCredential right) {
        if (left.getScope() != right.getScope()) {
            return false;
        }
        if (left.getScope() == SudoSecretScope.SERVER) {
            return left.getServerConnectionId() != null
                && left.getServerConnectionId().equals(right.getServerConnectionId());
        }
        return left.getGroupName() != null && left.getGroupName().equals(right.getGroupName());
    }

    private JAXBContext jaxbContext() throws Exception {
        return JAXBContext.newInstance(
            SchedulerData.class,
            ScheduledJob.class,
            JobSchedule.class,
            JobAction.class,
            JobActionType.class,
            JobArchiveFormat.class,
            JournalDetailMode.class,
            SftpSyncDirection.class,
            RsyncDirection.class,
            SudoCredential.class,
            SudoSecretScope.class,
            PinnedHostKey.class,
            JobJournalEntry.class,
            JobRunStatus.class
        );
    }

    @XmlRootElement(name = "jobScheduler")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class SchedulerData {

        @XmlElementWrapper(name = "jobs")
        @XmlElement(name = "job")
        private List<ScheduledJob> jobs = new ArrayList<>();

        @XmlElementWrapper(name = "sudoCredentials")
        @XmlElement(name = "sudoCredential")
        private List<SudoCredential> sudoCredentials = new ArrayList<>();

        @XmlElementWrapper(name = "pinnedHostKeys")
        @XmlElement(name = "pinnedHostKey")
        private List<PinnedHostKey> pinnedHostKeys = new ArrayList<>();

        @XmlElementWrapper(name = "journal")
        @XmlElement(name = "journalEntry")
        private List<JobJournalEntry> journal = new ArrayList<>();

        @XmlElement
        private boolean aiAgentAutoApproveDefaultMigrated;

        public List<ScheduledJob> getJobs() {
            if (jobs == null) {
                jobs = new ArrayList<>();
            }
            return jobs;
        }

        public void setJobs(List<ScheduledJob> jobs) {
            this.jobs = jobs != null ? jobs : new ArrayList<>();
        }

        public List<SudoCredential> getSudoCredentials() {
            if (sudoCredentials == null) {
                sudoCredentials = new ArrayList<>();
            }
            return sudoCredentials;
        }

        public void setSudoCredentials(List<SudoCredential> sudoCredentials) {
            this.sudoCredentials = sudoCredentials != null ? sudoCredentials : new ArrayList<>();
        }

        public List<PinnedHostKey> getPinnedHostKeys() {
            if (pinnedHostKeys == null) {
                pinnedHostKeys = new ArrayList<>();
            }
            return pinnedHostKeys;
        }

        public void setPinnedHostKeys(List<PinnedHostKey> pinnedHostKeys) {
            this.pinnedHostKeys = pinnedHostKeys != null ? pinnedHostKeys : new ArrayList<>();
        }

        public List<JobJournalEntry> getJournal() {
            if (journal == null) {
                journal = new ArrayList<>();
            }
            return journal;
        }

        public void setJournal(List<JobJournalEntry> journal) {
            this.journal = journal != null ? journal : new ArrayList<>();
        }

        public boolean isAiAgentAutoApproveDefaultMigrated() {
            return aiAgentAutoApproveDefaultMigrated;
        }

        public void setAiAgentAutoApproveDefaultMigrated(boolean aiAgentAutoApproveDefaultMigrated) {
            this.aiAgentAutoApproveDefaultMigrated = aiAgentAutoApproveDefaultMigrated;
        }

        void normalize() {
            getJobs();
            getSudoCredentials();
            getPinnedHostKeys();
            getJournal().forEach(JobJournalEntry::getId);
            migrateAiAgentAutoApproveDefault();
        }

        private void migrateAiAgentAutoApproveDefault() {
            if (aiAgentAutoApproveDefaultMigrated) {
                return;
            }
            for (ScheduledJob job : getJobs()) {
                JobAction action = job != null ? job.getAction() : null;
                if (action != null && action.getType() == JobActionType.AI_AGENT) {
                    action.setAiAutoApproveCommands(true);
                }
            }
            aiAgentAutoApproveDefaultMigrated = true;
        }
    }
}
