package io.github.delucagit.dockline.token;

import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Chooses the token store with {@code dockline.token-store}: {@code credential-manager} (default) or
 * {@code memory}. At start-up every already stored token is read once, so it is masked in logs from the
 * first moment; if the store cannot be opened, that is reported in the Log section and the app keeps running.
 */
@Configuration
class TokenStoreConfig {

    @Bean
    TokenStore tokenStore(@Value("${dockline.token-store:credential-manager}") String type) {
        TokenStore store = switch (type) {
            case "credential-manager" -> new CredentialManagerTokenStore(CredentialManagerTokenStore.SERVICE);
            case "memory" -> new InMemoryTokenStore();
            default -> throw new IllegalArgumentException(
                    "dockline.token-store must be 'credential-manager' or 'memory', not '" + type + "'");
        };
        return new MaskingTokenStore(store);
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerStoredTokensWithTheMasker(ApplicationReadyEvent event) {
        TokenStore store = event.getApplicationContext().getBean(TokenStore.class);
        ProblemLog problemLog = event.getApplicationContext().getBean(ProblemLog.class);
        try {
            for (Source source : Source.values()) {
                store.read(source);
            }
        } catch (TokenStoreUnavailableException e) {
            problemLog.report(LogLevel.ERROR, LogSource.BACKEND, "Open token store", null,
                    e.getMessage() + ". GitHub and Hostinger connections cannot be saved.", null, null, e);
        }
    }
}
