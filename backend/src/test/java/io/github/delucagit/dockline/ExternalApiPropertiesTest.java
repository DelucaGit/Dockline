package io.github.delucagit.dockline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The external API addresses default to the real APIs and can be pointed at a fake server. */
class ExternalApiPropertiesTest {

    @Nested
    @SpringBootTest
    class Defaults {
        @Autowired ExternalApiProperties apis;

        @Test
        void pointAtTheRealApis() {
            assertThat(apis.github().apiUrl()).isEqualTo("https://api.github.com");
            assertThat(apis.github().oauthUrl()).isEqualTo("https://github.com");
            assertThat(apis.hostinger().apiUrl()).isEqualTo("https://developers.hostinger.com");
        }
    }

    @Nested
    @SpringBootTest(properties = {
        "DOCKLINE_GITHUB_API_URL=http://127.0.0.1:9001",
        "DOCKLINE_GITHUB_OAUTH_URL=http://127.0.0.1:9002",
        "DOCKLINE_HOSTINGER_API_URL=http://127.0.0.1:9003"
    })
    class Overridden {
        @Autowired ExternalApiProperties apis;

        @Test
        void followTheEnvironmentVariables() {
            assertThat(apis.github().apiUrl()).isEqualTo("http://127.0.0.1:9001");
            assertThat(apis.github().oauthUrl()).isEqualTo("http://127.0.0.1:9002");
            assertThat(apis.hostinger().apiUrl()).isEqualTo("http://127.0.0.1:9003");
        }
    }
}
