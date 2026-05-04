package de.kortty.jobscheduler;

import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;

import java.util.Optional;

public class JobSchedulerSudoService {

    private final JobSchedulerRepository repository;
    private final EncryptionService encryptionService = new EncryptionService();

    public JobSchedulerSudoService(JobSchedulerRepository repository) {
        this.repository = repository;
    }

    public Optional<String> resolveSudoPassword(ServerConnection connection, char[] masterPassword) throws Exception {
        if (connection == null || masterPassword == null) {
            return Optional.empty();
        }
        Optional<SudoCredential> serverCredential = repository.findServerSudoCredential(connection.getId());
        if (serverCredential.isPresent()) {
            return decrypt(serverCredential.get(), masterPassword);
        }
        Optional<SudoCredential> groupCredential = repository.findGroupSudoCredential(connection.getGroup());
        if (groupCredential.isPresent()) {
            return decrypt(groupCredential.get(), masterPassword);
        }
        return Optional.empty();
    }

    public void setServerSudoPassword(String connectionId, String password, char[] masterPassword) throws Exception {
        SudoCredential credential = new SudoCredential();
        credential.setScope(SudoSecretScope.SERVER);
        credential.setServerConnectionId(connectionId);
        credential.setEncryptedPassword(encryptionService.encryptPassword(password, masterPassword));
        repository.upsertSudoCredential(credential);
    }

    public void setGroupSudoPassword(String groupName, String password, char[] masterPassword) throws Exception {
        SudoCredential credential = new SudoCredential();
        credential.setScope(SudoSecretScope.GROUP);
        credential.setGroupName(groupName);
        credential.setEncryptedPassword(encryptionService.encryptPassword(password, masterPassword));
        repository.upsertSudoCredential(credential);
    }

    private Optional<String> decrypt(SudoCredential credential, char[] masterPassword) throws Exception {
        if (credential.getEncryptedPassword() == null || credential.getEncryptedPassword().isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(encryptionService.decryptPassword(credential.getEncryptedPassword(), masterPassword));
    }
}
