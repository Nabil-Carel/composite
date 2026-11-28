package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Verifies that clients can see what went wrong and how to fix it. */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CompositeErrorMessagesIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    private static String sub(String referenceId, String method, String url, String extra) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"" + method + "\",\"url\":\"" + url + "\""
            + (extra == null ? "" : "," + extra) + "}";
    }

    private static String batch(String... subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/api/composite/execute", new HttpEntity<>(body, headers), String.class);
    }

    private static List<String> errors(ResponseEntity<String> response) throws Exception {
        List<String> errors = new ArrayList<>();
        JSON.readTree(response.getBody()).path("errors").forEach(error -> errors.add(error.asText()));
        return errors;
    }

    static Stream<Arguments> invalidRequests() {
        String secret = sub("s", "GET", "/api/sec/secret", null);
        String tooMany = batch(IntStream.range(0, 26)
            .mapToObj(i -> sub("r" + i, "GET", "/api/sec/secret", null)).toArray(String[]::new));
        return Stream.of(
            Arguments.of("{not json", "not valid composite JSON near line 1"),
            Arguments.of("{\"allOrNone\":true}", "Unknown composite request field: allOrNone. Remove it"),
            Arguments.of("{\"subRequests\":[{\"uri\":\"/x\"}]}", "Unknown sub-request field: uri. Remove it"),
            Arguments.of("{}", "At least one sub-request is required"),
            Arguments.of("{\"subRequests\":[null]}", "Remove null entries"),
            Arguments.of(tooMany, "26 exceeds the maximum of 25. Split"),
            Arguments.of(batch(secret, secret), "Duplicate reference ID found: 's'. Each sub-request"),
            Arguments.of(batch(sub("a", "GET", "/api/sec/users/${missing.id}", null)),
                "Missing dependency reference: 'missing' for request: a. Add"),
            Arguments.of(batch(sub("a", "GET", "/api/sec/users/${a.id}", null)),
                "Circular dependency involving: a."),
            Arguments.of(batch(sub("a", "TRACE", "/api/sec/secret", null)), "subRequests[0].method: Invalid HTTP method"),
            Arguments.of("{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\"}]}", "subRequests[0].url: URL is required"),
            Arguments.of(batch(sub("a", "POST", "/api/sec/echo", null)), "subRequests[0].body: Body is required"),
            Arguments.of(batch(sub("a", "GET", "/api/sec/secret", "\"body\":{\"x\":1}")),
                "[a] Request body is not allowed for GET method. Remove"),
            Arguments.of(batch(sub("a", "GET", "http://evil.example/api", null)),
                "[a] Invalid URL format: http://evil.example/api. Use an application-relative path"),
            Arguments.of(batch(sub("a", "GET", "/api/sec/hidden-route", null)),
                "[a] Endpoint not available for composite execution: GET /api/sec/hidden-route. Check"),
            Arguments.of(batch(sub("a", "GET", "/api/sec/headers", "\"headers\":{\"Authorization\":\"x\"}")),
                "[a] Header not allowed on a sub-request: Authorization. Remove it"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequest_explainsTheProblemAndFix(String body, String expected) throws Exception {
        ResponseEntity<String> response = post(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(response)).anyMatch(error -> error.contains(expected));
    }

    @Test
    void duplicateReference_isReportedOnce() throws Exception {
        String secret = sub("s", "GET", "/api/sec/secret", null);

        assertThat(errors(post(batch(secret, secret))))
            .filteredOn(error -> error.contains("Duplicate reference ID")).hasSize(1);
    }

    @Test
    void malformedUrl_doesNotAlsoReportAMissingEndpoint() throws Exception {
        assertThat(errors(post(batch(sub("a", "GET", "/api/sec/../secret", null)))))
            .hasSize(1)
            .allMatch(error -> error.contains("Invalid URL format"));
    }

    static Stream<Arguments> subRequestFailures() {
        return Stream.of(
            Arguments.of("${v.missing}", "Property 'missing' not found in response 'v'. References use"),
            Arguments.of("${v.age.more}", "Property 'more' not found"),
            Arguments.of("x ${v}", "is an object or array"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"${v.}", "${v[99999999999]}", "${${v.id}}"})
    void malformedReference_isRejectedBeforeAnySubRequestRuns(String reference) throws Exception {
        TestSecurityController.reset();

        ResponseEntity<String> response = post(batch(
            sub("v", "POST", "/api/sec/echo", "\"body\":{\"x\":1}"),
            sub("w", "POST", "/api/sec/echo", "\"body\":{\"x\":\"" + reference + "\"}")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errors(response)).anyMatch(error -> error.startsWith("[w] Invalid reference in the body"));
        assertThat(TestSecurityController.lastEcho).isNull();
    }

    @ParameterizedTest
    @MethodSource("subRequestFailures")
    void referenceFailure_explainsTheProblem(String reference, String expected) throws Exception {
        JsonNode response = JSON.readTree(post(batch(
            sub("v", "GET", "/api/sec/users/1", null),
            sub("w", "POST", "/api/sec/echo", "\"body\":{\"x\":\"" + reference + "\"}"))).getBody());

        assertThat(response.at("/responses/w/httpStatus").asInt()).isEqualTo(400);
        assertThat(response.at("/responses/w/body").asText()).contains(expected);
    }

    @Test
    void failedDependency_namesTheFailedSubRequest() throws Exception {
        JsonNode response = JSON.readTree(post(batch(
            sub("bad", "GET", "/api/sec/missing", null),
            sub("after", "POST", "/api/sec/echo", "\"body\":{\"x\":\"${bad.id}\"}"))).getBody());

        assertThat(response.at("/responses/after/body").asText())
            .isEqualTo("Failed Dependency: not executed because these sub-requests failed: bad");
    }

    @Test
    void routeRefusedAfterDispatch_explainsWhy() throws Exception {
        TestSecurityController.reset();

        JsonNode response = JSON.readTree(post(batch(
            sub("a", "GET", "/api/sec/users/functional", null))).getBody());

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(403);
        assertThat(response.at("/responses/a/body").asText()).contains("not available for composite execution");
    }

    @Test
    void oversizedResponse_explainsWhyWithoutInternals() throws Exception {
        JsonNode response = JSON.readTree(post(batch(sub("a", "GET", "/api/json/large", null))).getBody());

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
        assertThat(response.at("/responses/a/body").asText())
            .isEqualTo("Error executing subrequest: the response is larger than the allowed size.");
    }
}
