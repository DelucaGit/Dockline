package io.github.delucagit.dockline.status;

import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import io.github.delucagit.dockline.token.TokenStoreUnavailableException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The connection state of every source, in {@link Source} order. */
@Service
public class SourceStatuses {

    private final TokenStore tokenStore;
    private final Map<Source, SourceStatusProvider> providers = new EnumMap<>(Source.class);

    SourceStatuses(TokenStore tokenStore, List<SourceStatusProvider> providers) {
        this.tokenStore = tokenStore;
        for (SourceStatusProvider provider : providers) {
            if (this.providers.putIfAbsent(provider.source(), provider) != null) {
                throw new IllegalStateException("More than one SourceStatusProvider for " + provider.source());
            }
        }
    }

    public List<SourceStatus> all() {
        return Arrays.stream(Source.values()).map(this::of).toList();
    }

    public SourceStatus of(Source source) {
        SourceStatusProvider provider = providers.get(source);
        return provider != null ? provider.status() : fromStoredToken(source);
    }

    private SourceStatus fromStoredToken(Source source) {
        try {
            return SourceStatus.of(source,
                    tokenStore.read(source).isPresent() ? ConnectionState.CONNECTED : ConnectionState.NOT_CONNECTED);
        } catch (TokenStoreUnavailableException e) {
            return new SourceStatus(source, ConnectionState.ERROR, e.getMessage());
        }
    }
}
