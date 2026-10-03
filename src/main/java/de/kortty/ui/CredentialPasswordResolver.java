package de.kortty.ui;

import de.kortty.core.CredentialManager;
import de.kortty.model.StoredCredential;
import javafx.application.Platform;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Fills a dialog's password field from a stored credential without freezing the JavaFX thread.
 * A stored (encrypted) password is decrypted synchronously; an external password command runs on
 * a background thread, and its result is delivered on the UI thread only if no newer
 * {@link #resolve} or {@link #cancel} happened in the meantime, so a slow command can never
 * overwrite a later selection.
 *
 * <p>Confined to the UI thread: call {@link #resolve} and {@link #cancel} from it.
 */
final class CredentialPasswordResolver {

    /** Callbacks, all invoked on the UI thread. */
    interface Listener {
        /** An external password command started; the field should show its progress state. */
        void started(StoredCredential credential);

        /** The password, or {@code null} when a stored credential has none. */
        void resolved(StoredCredential credential, String password);

        void failed(StoredCredential credential, Exception error);
    }

    private final Executor uiExecutor;
    private long generation;
    private boolean pending;

    CredentialPasswordResolver() {
        this(Platform::runLater);
    }

    CredentialPasswordResolver(Executor uiExecutor) {
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
    }

    /** Resolves {@code credential}'s password, superseding any request still in flight. */
    void resolve(CredentialManager manager, StoredCredential credential, char[] masterPassword, Listener listener) {
        long request = ++generation;
        pending = false;
        if (credential.getPasswordType() != StoredCredential.PasswordType.EXTERNAL_COMMAND) {
            String password;
            try {
                password = manager.getPassword(credential, masterPassword);
            } catch (Exception e) {
                listener.failed(credential, e);
                return;
            }
            listener.resolved(credential, password);
            return;
        }
        pending = true;
        listener.started(credential);
        manager.getPasswordAsync(credential, masterPassword).whenComplete((password, error) ->
            uiExecutor.execute(() -> {
                if (request != generation) {
                    return; // A newer selection owns the field now.
                }
                pending = false;
                if (error != null) {
                    listener.failed(credential, asException(error));
                } else {
                    listener.resolved(credential, password);
                }
            }));
    }

    /** Drops the result of any request still in flight (the selection was cleared). */
    void cancel() {
        generation++;
        pending = false;
    }

    /** True while an external password command for the current request is running. */
    boolean isPending() {
        return pending;
    }

    private static Exception asException(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof Exception exception ? exception : new Exception(cause.getMessage(), cause);
    }
}
