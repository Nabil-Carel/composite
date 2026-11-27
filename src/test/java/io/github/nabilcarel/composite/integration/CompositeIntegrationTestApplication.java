package io.github.nabilcarel.composite.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/** Unsecured host application. Security is covered by the {@code secured} test application. */
@SpringBootApplication(exclude = {
    SecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    UserDetailsServiceAutoConfiguration.class
})
public class CompositeIntegrationTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(CompositeIntegrationTestApplication.class, args);
    }
}
