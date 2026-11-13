package io.github.delucagit.dockline.token;

import java.util.Optional;

/**
 * Keeps one token per {@link Source}. The real store is Windows Credential Manager, so tokens survive an
 * app restart and never end up in a file; tests use an in-memory store ({@code dockline.token-store=memory}).
 *
 * <p>Every token that goes in or comes out is registered with
 * {@link io.github.delucagit.dockline.log.SecretMasker}, so it is masked in logs and API responses.
 * Callers must still never return a token from an endpoint.
 *
 * <p>Any method throws {@link TokenStoreUnavailableException} when the operating system's store cannot be used.
 */
public interface TokenStore {

    /** Stores the token for this source, replacing any earlier one. */
    void save(Source source, String token);

    /** The stored token for this source, or empty when none is stored. */
    Optional<String> read(Source source);

    /** Removes the token for this source. Does nothing when none is stored. */
    void delete(Source source);
}
