package io.github.delucagit.dockline.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

/**
 * Logback pattern word {@code %mask(...)}: runs the formatted text inside it (message and stack trace)
 * through {@link SecretMasker}, so no token reaches a log file or the console. Registered in logback-spring.xml.
 */
public class MaskingConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return SecretMasker.mask(in);
    }
}
