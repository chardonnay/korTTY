package de.kortty.core;

import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.model.GPGKey;
import de.kortty.model.SSHKey;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.persistence.XMLConnectionRepository;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Every data store keeps the user's file when it cannot load it. A corrupt file used to become
 * permanent loss: the manager started with an empty list and the next save (at the latest the
 * shutdown save) wrote that empty list over the recoverable file. Now a corrupt file is moved
 * aside with its original bytes, a file that cannot be read or moved stays in place and blocks
 * saving, and the stores that hold secrets are written owner-only.
 */
class StoreCorruptionRecoveryTest {

    private static final byte[] TRUNCATED = "<?xml version=\"1.0\"?>\n<root><entry><name>cut"
        .getBytes(StandardCharsets.UTF_8);

    /** One data store, driven the way korTTY drives it. */
    private interface Store {
        void load() throws Exception;

        /** The save the UI calls when it reports failures (ConfigurationManager: saveOrThrow). */
        void save() throws Exception;

        void addEntry();

        int size();

        Optional<Path> loadFailureBackup();

        boolean isSaveBlocked();
    }

    private record StoreKind(String fileName, boolean loadThrowsWhenBlocked, boolean ownerOnly,
                             Function<Path, Store> factory) {
        @Override
        public String toString() {
            return fileName;
        }
    }

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-store-recovery");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDir() throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        boolean posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null;
        if (posix) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (posix && Files.isRegularFile(path)) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                }
                Files.deleteIfExists(path);
            }
        }
    }

    @DataProvider(name = "stores")
    Object[][] stores() {
        return new Object[][] {
            {new StoreKind(XMLConnectionRepository.CONNECTIONS_FILE, false, true, StoreCorruptionRecoveryTest::connections)},
            {new StoreKind(CredentialManager.CREDENTIALS_FILE, true, true, StoreCorruptionRecoveryTest::credentials)},
            {new StoreKind(SSHKeyManager.SSH_KEYS_FILE, true, true, StoreCorruptionRecoveryTest::sshKeys)},
            {new StoreKind(GPGKeyManager.GPG_KEYS_FILE, true, false, StoreCorruptionRecoveryTest::gpgKeys)},
            {new StoreKind(EnvironmentManager.ENVIRONMENTS_FILE, false, false, StoreCorruptionRecoveryTest::environments)},
            {new StoreKind(JobSchedulerRepository.FILE_NAME, true, true, StoreCorruptionRecoveryTest::jobs)},
        };
    }

    @Test(dataProvider = "stores")
    void corruptFileIsQuarantinedWithItsOriginalBytes(StoreKind kind) throws Exception {
        Path file = Files.write(dir.resolve(kind.fileName()), TRUNCATED);
        Store store = kind.factory().apply(dir);

        store.load();

        assertThat(Files.exists(file)).isFalse();
        Path backup = store.loadFailureBackup().orElseThrow();
        assertThat(backup.getParent()).isEqualTo(dir);
        assertThat(backup.getFileName().toString())
            .matches(java.util.regex.Pattern.quote(kind.fileName()) + "\\.corrupt-\\d{8}-\\d{6}");
        assertThat(Files.readAllBytes(backup)).isEqualTo(TRUNCATED);
        assertThat(store.size()).isEqualTo(0);
        assertThat(store.isSaveBlocked()).isFalse();
    }

    @Test(dataProvider = "stores")
    void nextSaveWritesAFreshFileAndKeepsTheQuarantinedCopy(StoreKind kind) throws Exception {
        Path file = Files.write(dir.resolve(kind.fileName()), TRUNCATED);
        Store store = kind.factory().apply(dir);
        store.load();
        Path backup = store.loadFailureBackup().orElseThrow();

        store.addEntry();
        store.save();

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readAllBytes(backup)).isEqualTo(TRUNCATED);
        Store reloaded = kind.factory().apply(dir);
        reloaded.load();
        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.loadFailureBackup()).isEmpty();
        assertThat(tempFiles()).isEmpty();
    }

    @Test(dataProvider = "stores")
    void unreadableFileThatCannotBeMovedAsideIsNeverOverwritten(StoreKind kind) throws Exception {
        requireEnforcedPosixPermissions();
        Path file = Files.write(dir.resolve(kind.fileName()), TRUNCATED);
        Store store = kind.factory().apply(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        try {
            if (kind.loadThrowsWhenBlocked()) {
                expectThrows(Exception.class, store::load);
            } else {
                store.load();
            }
            assertThat(store.isSaveBlocked()).isTrue();
            assertThat(store.loadFailureBackup()).isEmpty();

            store.addEntry();
            IllegalStateException refused = expectThrows(IllegalStateException.class, store::save);
            assertThat(refused).hasMessageThat().contains(kind.fileName());
            if (store instanceof ConnectionsStore connections) {
                // The eight MainWindow callers use the logging save(): it must not throw either.
                connections.manager.save(null);
            }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(TRUNCATED);
        assertThat(siblings()).containsExactly(kind.fileName());
    }

    @Test(dataProvider = "stores")
    void validFileThatCannotBeReadStaysInPlaceAndBlocksSaving(StoreKind kind) throws Exception {
        requireEnforcedPosixPermissions();
        Store writer = kind.factory().apply(dir);
        writer.addEntry();
        writer.save();
        Path file = dir.resolve(kind.fileName());
        byte[] valid = Files.readAllBytes(file);
        // No parse error at all: the file is fine, it just cannot be read right now (a virus
        // scanner, a lock, a network home directory that is briefly gone).
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        Store store = kind.factory().apply(dir);

        if (kind.loadThrowsWhenBlocked()) {
            expectThrows(IOException.class, store::load);
        } else {
            store.load();
        }

        assertThat(store.isSaveBlocked()).isTrue();
        assertThat(store.loadFailureBackup()).isEmpty();
        assertThat(siblings()).containsExactly(kind.fileName());
        expectThrows(IllegalStateException.class, store::save);

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readAllBytes(file)).isEqualTo(valid);
        // Once the file is readable again, the next load lifts the block.
        store.load();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.isSaveBlocked()).isFalse();
        store.save();
    }

    @Test(dataProvider = "stores")
    void secretStoresAreOwnerOnlyAfterSave(StoreKind kind) throws Exception {
        Store store = kind.factory().apply(dir);
        store.addEntry();
        store.save();
        Path file = dir.resolve(kind.fileName());
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            throw new SkipException("POSIX file attributes are not supported on this platform");
        }
        // As an older korTTY left it: created with the default umask.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        store.addEntry();
        store.save();

        String expected = kind.ownerOnly() ? "rw-------" : "rw-r--r--";
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo(expected);
        assertThat(tempFiles()).isEmpty();
    }

    @Test(dataProvider = "stores")
    void failedReloadKeepsTheInMemoryEntries(StoreKind kind) throws Exception {
        Store store = kind.factory().apply(dir);
        store.addEntry();
        store.save();
        store.load();
        assertThat(store.size()).isEqualTo(1);

        // E.g. a backup import that restored a damaged file, then reloaded every store.
        Files.write(dir.resolve(kind.fileName()), TRUNCATED);
        store.load();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.loadFailureBackup()).isPresent();
        assertThat(Files.readAllBytes(store.loadFailureBackup().orElseThrow())).isEqualTo(TRUNCATED);
    }

    // ---- The stores ----

    private static final class ConnectionsStore implements Store {
        final ConfigurationManager manager;

        ConnectionsStore(Path dir) {
            manager = new ConfigurationManager(dir);
        }

        @Override
        public void load() {
            manager.load(null);
        }

        @Override
        public void save() throws Exception {
            manager.saveOrThrow(null);
        }

        @Override
        public void addEntry() {
            manager.addConnection(new ServerConnection("demo", "demo.example.test", 22, "demo"));
        }

        @Override
        public int size() {
            return manager.getConnections().size();
        }

        @Override
        public Optional<Path> loadFailureBackup() {
            return manager.getLoadFailureBackup();
        }

        @Override
        public boolean isSaveBlocked() {
            return manager.isSaveBlocked();
        }
    }

    private static Store connections(Path dir) {
        return new ConnectionsStore(dir);
    }

    private static Store credentials(Path dir) {
        CredentialManager manager = new CredentialManager(dir);
        return new Store() {
            @Override
            public void load() throws Exception {
                manager.load();
            }

            @Override
            public void save() throws Exception {
                manager.save();
            }

            @Override
            public void addEntry() {
                manager.addCredential(new StoredCredential("demo", "demo", StoredCredential.Environment.TEST));
            }

            @Override
            public int size() {
                return manager.getAllCredentials().size();
            }

            @Override
            public Optional<Path> loadFailureBackup() {
                return manager.getLoadFailureBackup();
            }

            @Override
            public boolean isSaveBlocked() {
                return manager.isSaveBlocked();
            }
        };
    }

    private static Store sshKeys(Path dir) {
        SSHKeyManager manager = new SSHKeyManager(dir);
        return new Store() {
            @Override
            public void load() throws Exception {
                manager.load();
            }

            @Override
            public void save() throws Exception {
                manager.save();
            }

            @Override
            public void addEntry() {
                manager.addKey(new SSHKey("demo", "/nonexistent/id_demo"));
            }

            @Override
            public int size() {
                return manager.getAllKeys().size();
            }

            @Override
            public Optional<Path> loadFailureBackup() {
                return manager.getLoadFailureBackup();
            }

            @Override
            public boolean isSaveBlocked() {
                return manager.isSaveBlocked();
            }
        };
    }

    private static Store gpgKeys(Path dir) {
        GPGKeyManager manager = new GPGKeyManager(dir);
        return new Store() {
            @Override
            public void load() throws Exception {
                manager.load();
            }

            @Override
            public void save() throws Exception {
                manager.save();
            }

            @Override
            public void addEntry() {
                manager.addKey(new GPGKey("demo", "ABCD1234"));
            }

            @Override
            public int size() {
                return manager.getAllKeys().size();
            }

            @Override
            public Optional<Path> loadFailureBackup() {
                return manager.getLoadFailureBackup();
            }

            @Override
            public boolean isSaveBlocked() {
                return manager.isSaveBlocked();
            }
        };
    }

    private static Store environments(Path dir) {
        EnvironmentManager manager = new EnvironmentManager(dir);
        int builtIns = StoredCredential.Environment.values().length;
        return new Store() {
            @Override
            public void load() {
                manager.load();
            }

            @Override
            public void save() throws Exception {
                manager.save();
            }

            @Override
            public void addEntry() {
                manager.addCustomEnvironment("Lab");
            }

            @Override
            public int size() {
                return manager.getEnvironments().size() - builtIns;
            }

            @Override
            public Optional<Path> loadFailureBackup() {
                return manager.getLoadFailureBackup();
            }

            @Override
            public boolean isSaveBlocked() {
                return manager.isSaveBlocked();
            }
        };
    }

    private static Store jobs(Path dir) {
        JobSchedulerRepository repository = new JobSchedulerRepository(dir);
        return new Store() {
            @Override
            public void load() throws Exception {
                repository.load();
            }

            @Override
            public void save() throws Exception {
                repository.save();
            }

            @Override
            public void addEntry() {
                ScheduledJob job = new ScheduledJob();
                job.setName("demo");
                repository.upsertJob(job);
            }

            @Override
            public int size() {
                return repository.getJobs().size();
            }

            @Override
            public Optional<Path> loadFailureBackup() {
                return repository.getLoadFailureBackup();
            }

            @Override
            public boolean isSaveBlocked() {
                return repository.isSaveBlocked();
            }
        };
    }

    // ---- Helpers ----

    private List<String> siblings() throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private List<String> tempFiles() throws IOException {
        return siblings().stream().filter(name -> name.endsWith(".tmp")).toList();
    }

    private void requireEnforcedPosixPermissions() {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null
            || "root".equals(System.getProperty("user.name"))) {
            throw new SkipException("needs POSIX permissions that apply to the current user");
        }
    }
}
