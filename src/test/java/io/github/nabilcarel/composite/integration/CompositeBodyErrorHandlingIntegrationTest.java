package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.model.request.CompositeRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A custom composite controller's own {@code @ExceptionHandler} wins for unreadable bodies, but
 * a global {@code @ControllerAdvice} does not change the built-in endpoint's error format.
 */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(CompositeBodyErrorHandlingIntegrationTest.Handlers.class)
class CompositeBodyErrorHandlingIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @TestConfiguration
    static class Handlers {

        @Bean
        CustomBatchController customBatchController() {
            return new CustomBatchController();
        }

        @Bean
        GlobalAdvice globalAdvice() {
            return new GlobalAdvice();
        }
    }

    @RestController
    static class CustomBatchController {

        @PostMapping("/custom/batch")
        Map<String, Object> batch(@RequestBody CompositeRequest request) {
            return Map.of("ok", true);
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<Map<String, Object>> unreadable() {
            return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).body(Map.of("handler", "controller"));
        }
    }

    @RestControllerAdvice
    static class GlobalAdvice {

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<Map<String, Object>> unreadable() {
            return ResponseEntity.unprocessableEntity().body(Map.of("handler", "advice"));
        }
    }

    private ResponseEntity<String> postMalformed(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>("{\"subRequests\":[", headers), String.class);
    }

    @Test
    void customControllersOwnHandler_wins() {
        ResponseEntity<String> response = postMalformed("/custom/batch");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.I_AM_A_TEAPOT);
        assertThat(response.getBody()).contains("controller");
    }

    @Test
    void globalAdvice_doesNotChangeTheBuiltInErrorFormat() {
        ResponseEntity<String> response = postMalformed("/api/composite/execute");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Request body is not valid composite JSON").doesNotContain("advice");
    }
}
