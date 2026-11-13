package io.github.delucagit.dockline.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The one place backend code reports a warning or error for the Log section. Each report is written to
 * the log file (masked) and kept in memory; only the newest {@value #MAX_ENTRIES} entries are kept.
 *
 * <pre>{@code
 * problemLog.report(LogLevel.ERROR, LogSource.GITHUB, "GET /user", 401, "GitHub rejected the token",
 *         "GET https://api.github.com/user", responseBody, null);
 * }</pre>
 */
@Service
public class ProblemLog {

    static final int MAX_ENTRIES = 500;

    private static final Logger LOG = LoggerFactory.getLogger("dockline.problems");

    private final String appVersion;
    private final AtomicLong nextId = new AtomicLong(1);
    /** Newest first. Guarded by itself. */
    private final Deque<LogEntry> entries = new ArrayDeque<>();

    ProblemLog(@Value("${dockline.app-version:unknown}") String appVersion) {
        this.appVersion = appVersion;
    }

    /**
     * Records a problem. All texts are masked before they are stored or written.
     *
     * @param action     what the backend was doing, e.g. {@code "GET /user"}
     * @param statusCode HTTP status involved, or null
     * @param request    the request that failed, or null
     * @param response   the response that came back, or null
     * @param error      the exception, or null; its stack trace becomes part of the entry
     * @return the stored entry
     */
    public LogEntry report(LogLevel level, LogSource source, String action, Integer statusCode, String message,
            String request, String response, Throwable error) {
        LogEntry entry = new LogEntry(nextId.getAndIncrement(), Instant.now(), level, source, action, statusCode,
                message, new LogEntry.Context(appVersion, request, response, stackTraceOf(error))).masked();
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeLast();
            }
        }
        LOG.atLevel(level == LogLevel.ERROR ? Level.ERROR : Level.WARN)
                .setCause(error)
                .log("{} {} {}{}: {}{}{}", source, level, action, statusCode == null ? "" : " -> " + statusCode,
                        message, request == null ? "" : "\n  request: " + request,
                        response == null ? "" : "\n  response: " + response);
        return entry;
    }

    /** Newest first, optionally only one source and/or level (null = all). Tokens are masked. */
    public List<LogEntry> recent(LogSource source, LogLevel level) {
        List<LogEntry> result = new ArrayList<>();
        synchronized (entries) {
            for (LogEntry entry : entries) {
                if ((source == null || entry.source() == source) && (level == null || entry.level() == level)) {
                    result.add(entry.masked());
                }
            }
        }
        return result;
    }

    /** Empties the list (the log files are kept). */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static String stackTraceOf(Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
