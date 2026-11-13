package io.github.delucagit.dockline.github;

import org.springframework.http.HttpStatus;

/** Starting the device flow failed; answered as a problem response with a message for the user. */
class GithubConnectException extends RuntimeException {

    private final HttpStatus status;

    GithubConnectException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
