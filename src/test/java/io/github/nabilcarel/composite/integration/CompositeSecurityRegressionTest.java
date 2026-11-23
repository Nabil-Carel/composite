package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.nabilcarel.composite.integration.controllers.TestSecurityController;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression tests for the reproduced composite security findings. Each test states the
 * security property that must hold, independent of how it is implemented.
 */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@TestPropertySource(properties = {
    "composite.security.forwarded-headers=Authorization",
    "composite.security.client-allowed-headers=X-From-Ref"
})
class CompositeSecurityRegressionTest {

    private static final List<String> OUTSIDE_REQUESTS = new CopyOnWriteArrayList<>();
    private static HttpServer outsideServer;
    private static int outsidePort;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void startOutsideServer() throws Exception {
        outsideServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        outsideServer.createContext("/", exchange -> {
            OUTSIDE_REQUESTS.add(exchange.getRequestURI() + " auth="
                + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"leaked\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        outsideServer.start();
        outsidePort = outsideServer.getAddress().getPort();
    }

    @AfterAll
    static void stopOutsideServer() {
        outsideServer.stop(0);
    }

    @BeforeEach
    void reset() {
        OUTSIDE_REQUESTS.clear();
        TestSecurityController.reset();
    }

    // ========== #1: targets must stay on this application ==========

    @Test
    void absoluteUrl_isRejected_andNeverLeavesTheApplication() {
        ResponseEntity<Map<String, Object>> response = execute(
            get("a", "http://127.0.0.1:" + outsidePort + "/api/sec/users/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(OUTSIDE_REQUESTS).isEmpty();
    }

    @Test
    void schemeRelativeUrl_isRejected_andNeverLeavesTheApplication() {
        ResponseEntity<Map<String, Object>> response = execute(
            get("a", "//127.0.0.1:" + outsidePort + "/api/sec/users/1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(OUTSIDE_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/\\127.0.0.1/api/sec/users/1",
        "/api/sec/users/1#fragment",
        "/api/sec/users/../users/1",
        "/api/sec/users/./1",
        "/api/sec/users/..%2f1",
        "/api/sec/users/%2e%2e",
        "/api/sec/users/me;x=1",
        "api/sec/users/1"
    })
    void malformedTargets_areRejected(String url) {
        ResponseEntity<Map<String, Object>> response = execute(get("a", url));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
    }

    // ========== #2 and new routing blocker: only annotated handlers run ==========

    @Test
    void queryStringCannotSatisfyTheAllowlist() {
        execute(get("a", "/api/sec/items/1?/details"));

        assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
    }

    @Test
    void literalPath_routedToUnannotatedHandler_isRejected() {
        ResponseEntity<Map<String, Object>> response = execute(get("a", "/api/sec/users/me"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
    }

    @Test
    void responseValue_routedToUnannotatedHandler_isRejected() {
        ResponseEntity<Map<String, Object>> response = execute(
            get("a", "/api/sec/users/1"),
            get("b", "/api/sec/users/${a.me}"));

        assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
        assertThat(subStatus(response, "b")).isEqualTo(400);
    }

    // ========== #3: stored data is never treated as a template ==========

    @Test
    void storedPlaceholder_staysLiteral() {
        execute(
            get("secret", "/api/sec/secret"),
            get("a", "/api/sec/stored"),
            post("b", "/api/sec/echo",
                Map.of("copy", "x ${a.template}", "dep", "${secret.token}")));

        assertThat(TestSecurityController.lastEcho).containsEntry("copy", "x ${secret.token}");
    }

    @Test
    void storedBarePath_staysLiteral() {
        execute(
            get("secret", "/api/sec/secret"),
            get("a", "/api/sec/stored"),
            post("b", "/api/sec/echo", Map.of("copy", "${a.bare}", "dep", "${secret.token}")));

        assertThat(TestSecurityController.lastEcho).containsEntry("copy", "secret.token");
    }

    // ========== #4: values cannot change the structure of the URL ==========

    @Test
    void valueWithQuery_staysInsideOnePathSegment() {
        execute(
            get("a", "/api/sec/users/1"),
            get("b", "/api/sec/users/${a.evil}"));

        assertThat(TestSecurityController.lastId).isEqualTo("1?role=admin");
        assertThat(TestSecurityController.lastQuery).isNull();
    }

    @Test
    void valueWithSemicolon_cannotReachShadowedHandler() {
        execute(
            get("a", "/api/sec/users/1"),
            get("b", "/api/sec/users/${a.semicolon}"));

        assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
        assertThat(TestSecurityController.lastId).isEqualTo("me;x=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dots", "slash"})
    void traversalValues_failTheSubRequest(String field) {
        ResponseEntity<Map<String, Object>> response = execute(
            get("a", "/api/sec/users/1"),
            get("b", "/api/sec/items/${a." + field + "}/details"));

        assertThat(subStatus(response, "b")).isEqualTo(400);
        assertThat(TestSecurityController.HIDDEN_CALLS).hasValue(0);
    }

    @Test
    void valueInQuery_cannotAddParameters() {
        execute(
            get("a", "/api/sec/users/1"),
            get("b", "/api/sec/users/2?name=${a.evil}"));

        assertThat(TestSecurityController.lastId).isEqualTo("2");
        assertThat(TestSecurityController.lastQuery).isEqualTo("name=1%3Frole%3Dadmin");
    }

    // ========== #6: clients cannot set trusted headers ==========

    @ParameterizedTest
    @ValueSource(strings = {
        "X-Forwarded-User", "X-Forwarded-For", "Forwarded", "X-Real-IP", "Host",
        "Authorization", "Cookie", "Transfer-Encoding", "X-Composite-Request-Id",
        "X-Not-On-The-Allowlist"
    })
    void disallowedClientHeaders_areRejected(String headerName) {
        Map<String, Object> subRequest = get("a", "/api/sec/headers");
        subRequest.put("headers", Map.of(headerName, "spoofed"));

        ResponseEntity<Map<String, Object>> response = execute(subRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(TestSecurityController.lastHeaders).isNull();
    }

    // ========== #7 and #9: resolved values are sent with the right type ==========

    @Test
    void allowedHeader_receivesResolvedValue() {
        Map<String, Object> second = get("b", "/api/sec/headers");
        second.put("headers", Map.of("X-From-Ref", "${a.id}"));

        execute(get("a", "/api/sec/users/7"), second);

        assertThat(TestSecurityController.lastHeaders).containsEntry("x-from-ref", "7");
    }

    @Test
    void wholeValueReference_keepsItsJsonType() {
        execute(
            get("a", "/api/sec/users/1"),
            post("b", "/api/sec/echo", Map.of("age", "${a.age}")));

        assertThat(TestSecurityController.lastEcho).containsEntry("age", 30);
    }

    @Test
    void forwardedAuthorization_stillReachesAllowlistedHandler() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("caller-token");

        execute(headers, get("a", "/api/sec/headers"));

        assertThat(TestSecurityController.lastHeaders)
            .containsEntry("authorization", "Bearer caller-token");
    }

    // ========== Helpers ==========

    private static Map<String, Object> get(String referenceId, String url) {
        Map<String, Object> subRequest = new LinkedHashMap<>();
        subRequest.put("referenceId", referenceId);
        subRequest.put("method", "GET");
        subRequest.put("url", url);
        return subRequest;
    }

    private static Map<String, Object> post(String referenceId, String url, Map<String, Object> body) {
        Map<String, Object> subRequest = get(referenceId, url);
        subRequest.put("method", "POST");
        subRequest.put("body", body);
        return subRequest;
    }

    @SafeVarargs
    private ResponseEntity<Map<String, Object>> execute(Map<String, Object>... subRequests) {
        return execute(new HttpHeaders(), subRequests);
    }

    @SafeVarargs
    private ResponseEntity<Map<String, Object>> execute(HttpHeaders headers, Map<String, Object>... subRequests) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        List<Map<String, Object>> list = new ArrayList<>(Arrays.asList(subRequests));
        return restTemplate.exchange(
            "/api/composite/execute",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("subRequests", list), headers),
            new ParameterizedTypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static int subStatus(ResponseEntity<Map<String, Object>> response, String referenceId) {
        Map<String, Object> responses = (Map<String, Object>) response.getBody().get("responses");
        Map<String, Object> subResponse = (Map<String, Object>) responses.get(referenceId);
        return (Integer) subResponse.get("httpStatus");
    }
}
