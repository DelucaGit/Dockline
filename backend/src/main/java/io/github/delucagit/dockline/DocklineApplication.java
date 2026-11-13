package io.github.delucagit.dockline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocklineApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocklineApplication.class, args);
    }
}
