package io.github.delucagit.dockline.token;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import java.util.Locale;
import java.util.Optional;

/**
 * Keeps tokens in Windows Credential Manager (through java-keyring), one generic credential per source
 * under the service name {@value #SERVICE}. They survive app restarts and are protected by the user's
 * Windows account.
 */
class CredentialManagerTokenStore implements TokenStore {

    /** The service name all Dockline tokens are stored under. */
    static final String SERVICE = "Dockline";

    private final String service;
    private Keyring keyring;

    CredentialManagerTokenStore(String service) {
        this.service = service;
    }

    @Override
    public void save(Source source, String token) {
        try {
            keyring().setPassword(service, account(source), token);
        } catch (PasswordAccessException e) {
            throw new TokenStoreUnavailableException(
                    "Could not save the " + source + " token in Windows Credential Manager", e);
        }
    }

    @Override
    public Optional<String> read(Source source) {
        Keyring store = keyring();
        try {
            return Optional.ofNullable(store.getPassword(service, account(source)));
        } catch (PasswordAccessException e) {
            // java-keyring reports a missing entry the same way as a failed read.
            return Optional.empty();
        }
    }

    @Override
    public void delete(Source source) {
        Keyring store = keyring();
        try {
            store.deletePassword(service, account(source));
        } catch (PasswordAccessException e) {
            // Nothing stored: nothing to delete.
        }
    }

    private synchronized Keyring keyring() {
        if (keyring == null) {
            try {
                keyring = Keyring.create();
            } catch (BackendNotSupportedException | RuntimeException | LinkageError e) {
                throw new TokenStoreUnavailableException(
                        "Windows Credential Manager is not available on this computer (" + e.getMessage() + ")", e);
            }
        }
        return keyring;
    }

    private static String account(Source source) {
        return source.name().toLowerCase(Locale.ROOT);
    }
}
