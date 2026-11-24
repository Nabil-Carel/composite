package io.github.nabilcarel.composite.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A minimal host whose handlers do almost no work, so the profile shows library overhead. */
@SpringBootApplication(exclude = {
    SecurityAutoConfiguration.class,
    SecurityFilterAutoConfiguration.class,
    UserDetailsServiceAutoConfiguration.class,
    OAuth2ResourceServerAutoConfiguration.class
})
public class ProfileApplication {

    /** Replaces the default WebClient dispatcher when {@code composite.profile.dispatcher=jdk}. */
    @Bean
    @ConditionalOnProperty(name = "composite.profile.dispatcher", havingValue = "jdk")
    SubRequestDispatcher jdkHttpDispatcher(Environment environment) {
        return new JdkHttpSubRequestDispatcher(
            () -> "http://localhost:" + environment.getProperty("local.server.port"), new ObjectMapper(), 100);
    }

    @RestController
    static class ItemController {

        @CompositeEndpoint
        @GetMapping("/profile/items/{id}")
        public Map<String, Object> item(@PathVariable("id") String id) {
            return Map.of(
                "id", id,
                "name", "Item " + id,
                "price", 19.10,
                "active", true,
                "tags", List.of("alpha", "beta", "gamma"),
                "owner", Map.of("id", "user-1", "email", "user@example.com"));
        }
    }
}
