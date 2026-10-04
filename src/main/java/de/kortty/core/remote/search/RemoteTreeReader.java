package de.kortty.core.remote.search;

import org.apache.sshd.sftp.client.SftpClient;

import java.io.IOException;
import java.util.List;

/** The two SFTP calls the walk needs; {@code SFTPSession} and a plain {@link SftpClient} both provide them. */
public interface RemoteTreeReader {

    /** The entries of a folder, without {@code .} and {@code ..} being required to be absent. */
    List<SftpClient.DirEntry> list(String folder) throws IOException;

    /** The attributes of {@code path} itself; a symlink is reported as a symlink, never followed. */
    SftpClient.Attributes lstat(String path) throws IOException;

    /** A reader over an SFTP client. */
    static RemoteTreeReader of(SftpClient client) {
        return new RemoteTreeReader() {
            @Override
            public List<SftpClient.DirEntry> list(String folder) throws IOException {
                List<SftpClient.DirEntry> entries = new java.util.ArrayList<>();
                for (SftpClient.DirEntry entry : client.readDir(folder)) {
                    entries.add(entry);
                }
                return entries;
            }

            @Override
            public SftpClient.Attributes lstat(String path) throws IOException {
                return client.lstat(path);
            }
        };
    }
}
