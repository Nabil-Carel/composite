package io.github.nabilcarel.composite.secured;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** End-to-end checks against a real Spring Security filter chain and method security. */
class CompositeSpringSecurityIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String get(String referenceId, String url) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"" + url + "\"}";
    }

    private static String write(String referenceId, String body) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"POST\",\"url\":\"/secure/write\",\"body\":"
            + body + "}";
    }

    private static String batch(String... subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    private static int status(JsonNode response, String referenceId) {
        return response.at("/responses/" + referenceId + "/httpStatus").asInt();
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String path, HttpHeaders headers, String body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Nested
    @SpringBootTest(
        classes = SecuredTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "server.servlet.context-path=/app",
        "composite.base-path=/batch"
    })
    class BearerToken {

        private static final String EXECUTE = "/batch/execute";

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SecuredController.reset();
        }

        private JsonNode execute(String token, String... subRequests) throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer(token), batch(subRequests));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            return JSON.readTree(response.getBody());
        }

        @Test
        void preAuthorize_allowsPermittedUser() throws Exception {
            JsonNode response = execute("root-token", get("a", "/secure/admin"));

            assertThat(status(response, "a")).isEqualTo(200);
            assertThat(SecuredController.ADMIN_CALLS).hasValue(1);
        }

        @Test
        void preAuthorize_deniesOnlyTheForbiddenSubRequest() throws Exception {
            JsonNode response = execute("alice-token",
                get("denied", "/secure/admin"),
                get("allowed", "/secure/me"),
                write("dependent", "{\"id\":\"${denied.id}\"}"));

            assertThat(status(response, "denied")).isEqualTo(403);
            assertThat(status(response, "allowed")).isEqualTo(200);
            assertThat(status(response, "dependent")).isEqualTo(424);
            assertThat(SecuredController.ADMIN_CALLS).hasValue(0);
            assertThat(SecuredController.WRITES).hasValue(0);
        }

        @Test
        void subRequest_runsAsTheCaller() throws Exception {
            JsonNode response = execute("alice-token", get("a", "/secure/me"));

            assertThat(response.at("/responses/a/body/name").asText()).isEqualTo("alice");
        }

        @Test
        void expressionUsingPathVariable_isEvaluatedPerSubRequest() throws Exception {
            JsonNode response = execute("alice-token",
                get("mine", "/secure/owner/alice"),
                get("other", "/secure/owner/root"));

            assertThat(status(response, "mine")).isEqualTo(200);
            assertThat(status(response, "other")).isEqualTo(403);
        }

        @Test
        void anonymousBatch_isRejectedBeforeAnythingRuns() {
            ResponseEntity<String> response = post(rest, EXECUTE, new HttpHeaders(),
                batch(write("a", "{\"x\":1}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SecuredController.WRITES).hasValue(0);
        }

        @Test
        void clientCannotSwitchIdentityInsideABatch() {
            String subRequest = "{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/secure/admin\","
                + "\"headers\":{\"Authorization\":\"Bearer root-token\"}}";

            ResponseEntity<String> response = post(rest, EXECUTE, bearer("alice-token"), batch(subRequest));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(SecuredController.ADMIN_CALLS).hasValue(0);
        }

        @Test
        void securedButUnannotatedEndpoint_isNotReachable() {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer("root-token"),
                batch(get("a", "/secure/hidden")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(SecuredController.HIDDEN_CALLS).hasValue(0);
        }

        @Test
        void corsPreflight_andResponseHeaders_work() {
            HttpHeaders preflight = new HttpHeaders();
            preflight.setOrigin(SecuredTestApplication.ALLOWED_ORIGIN);
            preflight.setAccessControlRequestMethod(HttpMethod.POST);
            preflight.setAccessControlRequestHeaders(List.of("authorization", "content-type"));
            ResponseEntity<String> preflightResponse = rest.exchange(EXECUTE, HttpMethod.OPTIONS,
                new HttpEntity<>(preflight), String.class);

            HttpHeaders actual = bearer("alice-token");
            actual.setOrigin(SecuredTestApplication.ALLOWED_ORIGIN);
            ResponseEntity<String> response = post(rest, EXECUTE, actual, batch(get("a", "/secure/me")));

            assertThat(preflightResponse.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(preflightResponse.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo(SecuredTestApplication.ALLOWED_ORIGIN);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .containsExactly(SecuredTestApplication.ALLOWED_ORIGIN);
        }
    }

    @Nested
    @SpringBootTest(
        classes = SecuredTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "test.auth=jwt")
    class RealJwt {

        private static final String EXECUTE = "/api/composite/execute";

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SecuredController.reset();
        }

        @Test
        void validToken_runsAsItsSubject_withItsRoles() throws Exception {
            String token = SecuredTestApplication.issueJwt("root", Duration.ofMinutes(5), "USER", "ADMIN");

            ResponseEntity<String> response = post(rest, EXECUTE, bearer(token),
                batch(get("me", "/secure/me"), get("admin", "/secure/admin")));

            JsonNode json = JSON.readTree(response.getBody());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json.at("/responses/me/body/name").asText()).isEqualTo("root");
            assertThat(status(json, "admin")).isEqualTo(200);
        }

        @Test
        void missingRole_deniesOnlyThatSubRequest() throws Exception {
            String token = SecuredTestApplication.issueJwt("alice", Duration.ofMinutes(5), "USER");

            ResponseEntity<String> response = post(rest, EXECUTE, bearer(token),
                batch(get("admin", "/secure/admin"), get("me", "/secure/me"),
                    write("dependent", "{\"id\":\"${admin.id}\"}")));

            JsonNode json = JSON.readTree(response.getBody());
            assertThat(status(json, "admin")).isEqualTo(403);
            assertThat(status(json, "me")).isEqualTo(200);
            assertThat(status(json, "dependent")).isEqualTo(424);
            assertThat(SecuredController.ADMIN_CALLS).hasValue(0);
            assertThat(SecuredController.WRITES).hasValue(0);
        }

        @Test
        void expiredToken_isRejectedBeforeAnythingRuns() throws Exception {
            String token = SecuredTestApplication.issueJwt("root", Duration.ofMinutes(-10), "USER", "ADMIN");

            ResponseEntity<String> response = post(rest, EXECUTE, bearer(token),
                batch(write("w", "{\"x\":1}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SecuredController.WRITES).hasValue(0);
        }

        @Test
        void tamperedToken_isRejectedBeforeAnythingRuns() throws Exception {
            String token = SecuredTestApplication.issueJwt("alice", Duration.ofMinutes(5), "USER");
            String[] parts = token.split("\\.");
            String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"root\",\"roles\":[\"ADMIN\"],\"exp\":" + (System.currentTimeMillis() / 1000 + 300) + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

            ResponseEntity<String> response = post(rest, EXECUTE, bearer(parts[0] + "." + forgedPayload + "." + parts[2]),
                batch(get("admin", "/secure/admin")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SecuredController.ADMIN_CALLS).hasValue(0);
        }
    }

    /** Logs in through the real form endpoint and obtains a real CSRF token. */
    private static HttpHeaders sessionHeaders(TestRestTemplate rest, String username) throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", "secret");
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> login = rest.postForEntity("/login", new HttpEntity<>(form, loginHeaders), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String session = cookies(login);

        HttpHeaders csrfRequest = new HttpHeaders();
        csrfRequest.add(HttpHeaders.COOKIE, session);
        ResponseEntity<String> csrf = rest.exchange("/secure/csrf", HttpMethod.GET,
            new HttpEntity<>(csrfRequest), String.class);
        String token = JSON.readTree(csrf.getBody()).get("token").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session + "; XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        return headers;
    }

    private static String cookies(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
            .map(cookie -> cookie.split(";", 2)[0])
            .filter(cookie -> cookie.startsWith("JSESSIONID="))
            .collect(Collectors.joining("; "));
    }

    @Nested
    @SpringBootTest(
        classes = SecuredTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "test.auth=session",
        "composite.security.forwarded-headers=Cookie,X-XSRF-TOKEN"
    })
    class SessionCookieWithCsrf {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SecuredController.reset();
        }

        @Test
        void validSessionAndCsrfToken_canWrite() throws Exception {
            ResponseEntity<String> response = post(rest, "/api/composite/execute", sessionHeaders(rest, "alice"),
                batch(get("me", "/secure/me"), write("w", "{\"owner\":\"${me.name}\"}")));

            JsonNode json = JSON.readTree(response.getBody());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(json, "me")).isEqualTo(200);
            assertThat(status(json, "w")).isEqualTo(200);
            assertThat(json.at("/responses/w/body/owner").asText()).isEqualTo("alice");
            assertThat(SecuredController.WRITES).hasValue(1);
        }

        @Test
        void missingCsrfToken_rejectsTheWholeBatch() throws Exception {
            HttpHeaders headers = sessionHeaders(rest, "alice");
            headers.remove("X-XSRF-TOKEN");

            ResponseEntity<String> response = post(rest, "/api/composite/execute", headers,
                batch(write("w", "{\"x\":1}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SecuredController.WRITES).hasValue(0);
        }

        @Test
        void preAuthorize_stillAppliesToSessionUser() throws Exception {
            ResponseEntity<String> response = post(rest, "/api/composite/execute", sessionHeaders(rest, "alice"),
                batch(get("a", "/secure/admin")));

            assertThat(status(JSON.readTree(response.getBody()), "a")).isEqualTo(403);
            assertThat(SecuredController.ADMIN_CALLS).hasValue(0);
        }
    }

    @Nested
    @SpringBootTest(
        classes = SecuredTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "test.auth=session",
        "composite.security.forwarded-headers=Cookie"
    })
    class SessionCookieWithoutForwardedCsrf {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SecuredController.reset();
        }

        @Test
        void writeSubRequest_failsClosed() throws Exception {
            ResponseEntity<String> response = post(rest, "/api/composite/execute", sessionHeaders(rest, "alice"),
                batch(get("read", "/secure/me"), write("w", "{\"x\":1}")));

            JsonNode json = JSON.readTree(response.getBody());
            assertThat(status(json, "read")).isEqualTo(200);
            assertThat(status(json, "w")).isEqualTo(403);
            assertThat(SecuredController.WRITES).hasValue(0);
        }
    }
}
