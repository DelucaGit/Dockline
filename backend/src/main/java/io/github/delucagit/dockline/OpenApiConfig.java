package io.github.delucagit.dockline;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    /** Fixed title and a relative server URL, so the exported openapi.json is the same on every build. */
    @Bean
    OpenAPI docklineOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Dockline backend API").version("0.1.0"))
                .servers(List.of(new Server().url("/")));
    }
}
