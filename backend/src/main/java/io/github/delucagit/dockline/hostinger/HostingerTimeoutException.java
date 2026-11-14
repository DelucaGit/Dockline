package io.github.delucagit.dockline.hostinger;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.web.client.ResourceAccessException;

/**
 * Hostinger was reached but did not answer within the read timeout ({@code dockline.hostinger.read-timeout}).
 * Thrown by {@link HostingerClient} instead of the plain {@link ResourceAccessException}, so the message can say
 * "took too long" rather than "could not reach". A connect timeout is not one of these: then Hostinger could not
 * be reached at all.
 */
class HostingerTimeoutException extends ResourceAccessException {

    private final Duration timeout;

    HostingerTimeoutException(Duration timeout, Throwable cause) {
        super("Hostinger did not answer within " + timeout.toSeconds() + " s");
        initCause(cause);
        this.timeout = timeout;
    }

    /** The read timeout that ran out. */
    Duration timeout() {
        return timeout;
    }

    /**
     * True when {@code e} (or one of its causes) is a read timeout: an {@link HttpTimeoutException} that is not an
     * {@link HttpConnectTimeoutException}.
     */
    static boolean isReadTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return false;
            }
            if (t instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
