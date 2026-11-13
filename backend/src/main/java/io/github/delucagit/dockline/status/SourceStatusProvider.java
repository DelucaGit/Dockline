package io.github.delucagit.dockline.status;

import io.github.delucagit.dockline.token.Source;

/**
 * Works out the real connection state of one source, e.g. by checking its stored token against the API.
 * Register an implementation as a Spring bean and {@link SourceStatuses} uses it for that source.
 *
 * <p>Sources without a provider fall back to "token stored = CONNECTED, none = NOT_CONNECTED".
 */
public interface SourceStatusProvider {

    /** The source this provider reports on. At most one provider per source. */
    Source source();

    /** The current state. Must not throw; report problems as {@link ConnectionState#ERROR} with a message. */
    SourceStatus status();
}
