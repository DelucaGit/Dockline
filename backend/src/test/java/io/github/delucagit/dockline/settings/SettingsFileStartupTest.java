package io.github.delucagit.dockline.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts the real app with {@code dockline.settings.dir} pointing at a folder that holds a corrupt settings
 * file: the app starts anyway, has no links, keeps the file as a backup, shows a warning through the Log
 * API, and saves new links to that folder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsFileStartupTest {

    private static final String CORRUPT = "{\"version\":1,\"siteRepoLinks\":{\"example.com\":\"octo";
    private static final Path DIR =
            Path.of("target", "test-settings", "startup-" + UUID.randomUUID()).toAbsolutePath();

    @DynamicPropertySource
    static void settingsDir(DynamicPropertyRegistry registry) throws IOException {
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve("settings.json"), CORRUPT);
        registry.add("dockline.settings.dir", DIR::toString);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    SiteRepoLinkRepository links;

    @Test
    void aCorruptSettingsFileDoesNotStopTheAppAndIsReportedInTheLogSection() throws Exception {
        assertThat(links.findAll()).isEmpty();

        List<String> backups = filesInDir().stream().filter(name -> name.startsWith("settings.corrupt-")).toList();
        assertThat(backups).hasSize(1);
        assertThat(Files.readString(DIR.resolve(backups.get(0)))).isEqualTo(CORRUPT);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/log?source=BACKEND&level=WARN"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode entries = new ObjectMapper().readTree(response.body());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("action").asString()).isEqualTo("Read settings file");
        assertThat(entries.get(0).get("message").asString()).contains("settings.json").contains(backups.get(0));

        links.save("example.com", "octocat/website");
        assertThat(new ObjectMapper().readTree(Files.readAllBytes(DIR.resolve("settings.json"))).toString())
                .isEqualTo("{\"version\":1,\"siteRepoLinks\":{\"example.com\":\"octocat/website\"}}");
    }

    private static List<String> filesInDir() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
