package io.github.delucagit.dockline.token;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps tokens in memory only; they are gone when the app stops. Used by tests and end-to-end tests. */
class InMemoryTokenStore implements TokenStore {

    private final Map<Source, String> tokens = new ConcurrentHashMap<>();

    @Override
    public void save(Source source, String token) {
        tokens.put(source, token);
    }

    @Override
    public Optional<String> read(Source source) {
        return Optional.ofNullable(tokens.get(source));
    }

    @Override
    public void delete(Source source) {
        tokens.remove(source);
    }
}
