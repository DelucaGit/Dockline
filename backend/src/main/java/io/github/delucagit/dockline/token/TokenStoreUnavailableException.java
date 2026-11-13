package io.github.delucagit.dockline.token;

/** The operating system's credential store could not be used (e.g. not running on Windows). */
public class TokenStoreUnavailableException extends RuntimeException {

    public TokenStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
