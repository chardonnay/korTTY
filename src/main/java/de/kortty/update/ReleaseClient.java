package de.kortty.update;

import java.io.IOException;

public interface ReleaseClient {
    UpdateRelease fetchLatestRelease() throws IOException, InterruptedException;
}
