package io.github.delucagit.dockline.settings;

/** The settings file could not be written; nothing was changed, neither on disk nor in memory. */
public class SettingsUnavailableException extends RuntimeException {

    public SettingsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
