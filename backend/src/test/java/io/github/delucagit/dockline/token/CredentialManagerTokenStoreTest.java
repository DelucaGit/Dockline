package io.github.delucagit.dockline.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The real Windows Credential Manager, only on Windows. Uses a throwaway service name (never "Dockline")
 * and removes its entries afterwards, so the user's real tokens are never touched.
 */
@EnabledOnOs(OS.WINDOWS)
class CredentialManagerTokenStoreTest {

    private final String service = "Dockline-test-" + UUID.randomUUID();
    private final CredentialManagerTokenStore store = new CredentialManagerTokenStore(service);

    @AfterEach
    void cleanUp() {
        for (Source source : Source.values()) {
            store.delete(source);
        }
    }

    @Test
    void savesReadsAndDeletesATokenPerSource() {
        assertThat(store.read(Source.GITHUB)).isEmpty();

        store.save(Source.GITHUB, "gho_credentialManagerRoundTrip123");
        store.save(Source.HOSTINGER, "hostinger-token-round-trip");

        assertThat(store.read(Source.GITHUB)).contains("gho_credentialManagerRoundTrip123");
        assertThat(store.read(Source.HOSTINGER)).contains("hostinger-token-round-trip");

        store.save(Source.GITHUB, "gho_replacedToken456");
        assertThat(store.read(Source.GITHUB)).contains("gho_replacedToken456");

        store.delete(Source.GITHUB);
        assertThat(store.read(Source.GITHUB)).isEmpty();
        assertThat(store.read(Source.HOSTINGER)).contains("hostinger-token-round-trip");
        store.delete(Source.GITHUB); // deleting again is harmless
    }

    @Test
    void aTokenSurvivesARestartBecauseANewStoreFindsIt() {
        store.save(Source.HOSTINGER, "hostinger-token-survives-restart");

        CredentialManagerTokenStore afterRestart = new CredentialManagerTokenStore(service);

        assertThat(afterRestart.read(Source.HOSTINGER)).contains("hostinger-token-survives-restart");
    }
}
