package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Verifies the documented defaults and host-application deployment settings. */
class CompositeConfigurationIntegrationTest {

    private static final String EXECUTE = "/api/composite/execute";

    private static String batch(String url) {
        return "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"" + url + "\"}]}";
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String path, String body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private static int subStatus(ResponseEntity<String> response) throws Exception {
        JsonNode json = new ObjectMapper().readTree(response.getBody());
        return json.at("/responses/a/httpStatus").asInt();
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class Defaults {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestSecurityController.reset();
        }

        @Test
        void authorizationAndCookie_areForwardedByDefault() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth("caller-token");
            headers.add(HttpHeaders.COOKIE, "SESSION=abc");

            post(rest, EXECUTE, batch("/api/sec/headers"), headers);

            assertThat(TestSecurityController.lastHeaders)
                .containsEntry("authorization", "Bearer caller-token")
                .containsEntry("cookie", "SESSION=abc");
        }

        @Test
        void trackingHeaders_areSentByDefault() {
            post(rest, EXECUTE, batch("/api/sec/headers"), new HttpHeaders());

            assertThat(TestSecurityController.lastHeaders)
                .containsEntry("x-composite-request", "true")
                .containsEntry("x-composite-sub-request-id", "a")
                .containsKey("x-composite-request-id");
        }

        @Test
        void staticPathWithQueryString_isAllowed() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE, batch("/api/sec/users/1?page=2&sort=name"),
                new HttpHeaders());

            assertThat(subStatus(response)).isEqualTo(200);
            assertThat(TestSecurityController.lastQuery).isEqualTo("page=2&sort=name");
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "server.servlet.context-path=/app")
    class ContextPath {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void subRequests_useTheContextPath() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE, batch("/api/sec/secret"), new HttpHeaders());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(subStatus(response)).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "server.servlet.context-path=/app",
        "spring.mvc.servlet.path=/mvc"
    })
    class ServletPath {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void subRequests_useTheServletPath() throws Exception {
            ResponseEntity<String> response = post(rest, "/mvc" + EXECUTE, batch("/api/sec/secret"),
                new HttpHeaders());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(subStatus(response)).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.controller.enabled=false")
    class ControllerDisabled {

        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private ApplicationContext context;
        @Autowired
        private CompositeProperties properties;

        @Test
        void builtInController_isNotRegistered() {
            ResponseEntity<String> response = post(rest, EXECUTE, batch("/api/sec/secret"), new HttpHeaders());

            assertThat(context.getBeanNamesForType(CompositeController.class)).isEmpty();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(properties.getController().isEnabled()).isFalse();
        }
    }
}
