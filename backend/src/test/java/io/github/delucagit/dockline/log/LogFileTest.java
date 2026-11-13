package io.github.delucagit.dockline.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import io.github.delucagit.dockline.DocklineApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the real app with its log folder set to a fresh directory, makes it report problems over
 * HTTP and reads the written log file.
 */
class LogFileTest {

    private static final Path LOG_DIR = Path.of("target", "test-logs", "log-file-test-" + UUID.randomUUID());
    private static ConfigurableApplicationContext app;
    private static int port;

    @BeforeAll
    static void startApp() {
        // Other tests in this JVM already set up logging; let the app set it up again for its own folder.
        LoggingSystem.get(LogFileTest.class.getClassLoader()).cleanUp();
        app = SpringApplication.run(DocklineApplication.class, "--server.port=0",
                "--dockline.log.dir=" + LOG_DIR.toAbsolutePath(), "--dockline.test-endpoints=true");
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stopApp() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void reportedProblemsAreWrittenToTheLogFileWithTokensMasked() throws Exception {
        post("/api/test/report-error?message=Written%20to%20file");
        post("/api/test/fail");

        String log = Files.readString(LOG_DIR.resolve("dockline.log"), StandardCharsets.UTF_8);

        assertThat(log).contains("Written to file").contains("Simulated unexpected failure")
                .contains("IllegalStateException").contains("ghp_" + SecretMasker.MASK);
        assertThat(log).doesNotContain(TestEndpoints.FAKE_TOKEN).doesNotContain("0123456789abcdef");
    }

    @Test
    void theLogFileRollsOverAndOldFilesAreRemoved() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var appender = (RollingFileAppender<?>) context.getLogger("ROOT").getAppender("FILE");
        var policy = (SizeAndTimeBasedRollingPolicy<?>) appender.getRollingPolicy();

        assertThat(Path.of(appender.getFile())).isEqualTo(LOG_DIR.toAbsolutePath().resolve("dockline.log"));
        assertThat(policy.getFileNamePattern()).contains("%d{yyyy-MM-dd}").contains("%i");
        assertThat(policy.getMaxHistory()).isEqualTo(14);
    }

    private static void post(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
    }
}
