package io.github.nabilcarel.composite.integration;

import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "composite.base-path=/api/composite",
    "composite.controller.enabled=true",
    "composite.debug-enabled=false"
})
class CompositeDebugDisabledIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testDebugDisabled_noDebugFieldInResponse() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build()
            ))
            .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CompositeRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<CompositeResponse> response = restTemplate.exchange(
            "/api/composite/execute",
            HttpMethod.POST,
            entity,
            CompositeResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDebug()).isNull();
    }

    @Test
    void testDebugDisabled_noDebugKeyInJson() {
        CompositeRequest request = CompositeRequest.builder()
            .subRequests(List.of(
                SubRequestDto.builder()
                    .method("GET")
                    .url("/api/users/user1")
                    .referenceId("user1")
                    .build()
            ))
            .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<CompositeRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/composite/execute",
            HttpMethod.POST,
            entity,
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("\"debug\"");
    }
}
