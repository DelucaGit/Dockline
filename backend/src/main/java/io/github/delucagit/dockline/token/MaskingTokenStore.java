package io.github.delucagit.dockline.token;

import io.github.delucagit.dockline.log.SecretMasker;
import java.util.Optional;

/** Wraps the real store so every token it handles is registered with {@link SecretMasker}. */
class MaskingTokenStore implements TokenStore {

    private final TokenStore delegate;

    MaskingTokenStore(TokenStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(Source source, String token) {
        SecretMasker.register(token);
        delegate.save(source, token);
    }

    @Override
    public Optional<String> read(Source source) {
        Optional<String> token = delegate.read(source);
        token.ifPresent(SecretMasker::register);
        return token;
    }

    @Override
    public void delete(Source source) {
        // The removed token stays registered: it may still appear in older log lines or responses.
        delegate.delete(source);
    }
}
