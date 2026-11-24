package io.github.nabilcarel.composite.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Exercises the library through a real secured host application. */
class SampleAppSecurityIntegrationTest {

    private static final String EXECUTE = "/api/composite/execute";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String get(String referenceId, String url) {
        return sub(referenceId, "GET", url, null);
    }

    private static String sub(String referenceId, String method, String url, String extra) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"" + method + "\",\"url\":\"" + url + "\""
            + (extra == null ? "" : "," + extra) + "}";
    }

    private static String createOrder(String referenceId, String item) {
        return sub(referenceId, "POST", "/api/orders", "\"body\":{\"item\":\"" + item + "\"}");
    }

    private static String batch(String... subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String path, HttpHeaders headers, String body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static JsonNode ok(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private static int status(JsonNode response, String referenceId) {
        return response.at("/responses/" + referenceId + "/httpStatus").asInt();
    }

    @Test
    void packagedRun_loadsTheLibraryFromTheJar() {
        String location = CompositeAutoConfiguration.class.getProtectionDomain().getCodeSource()
            .getLocation().getPath();

        if (Boolean.getBoolean("composite.expect.packaged")) {
            assertThat(location).endsWith(".jar").contains("composite-spring-boot-starter");
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class JwtAuthentication {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SampleController.reset();
        }

        private JsonNode as(String token, String... subRequests) throws Exception {
            return ok(post(rest, EXECUTE, bearer(token), batch(subRequests)));
        }

        static Stream<String> rejectedTokens() {
            return Stream.of(
                SampleTokens.expiringIn(Duration.ofMinutes(-5), "root", "USER", "ADMIN"),
                SampleTokens.signedByUntrustedKey("root", "USER", "ADMIN"),
                SampleTokens.unsigned("root", "USER", "ADMIN"),
                "not-a-jwt");
        }

        @ParameterizedTest
        @MethodSource("rejectedTokens")
        void invalidToken_rejectsTheBatchBeforeAnythingRuns(String token) {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer(token), batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void anonymousBatch_isRejectedBeforeAnythingRuns() {
            ResponseEntity<String> response = post(rest, EXECUTE, new HttpHeaders(), batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        static Stream<Arguments> authorizationMatrix() {
            return Stream.of(
                Arguments.of("PreAuthorize own", "GET", "/api/accounts/alice", 200, 200),
                Arguments.of("PreAuthorize other", "GET", "/api/accounts/bob", 403, 200),
                Arguments.of("PostAuthorize own", "GET", "/api/orders/1", 200, 200),
                Arguments.of("PostAuthorize other", "GET", "/api/orders/2", 403, 200),
                Arguments.of("Secured", "DELETE", "/api/orders/2", 403, 200),
                Arguments.of("RolesAllowed", "GET", "/api/audit", 403, 200),
                Arguments.of("URL rule", "GET", "/api/admin/report", 403, 200),
                Arguments.of("Public", "GET", "/api/public/status", 200, 200));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("authorizationMatrix")
        void everyAuthorizationStyle_appliesPerUser(String name, String method, String url, int alice, int admin)
                throws Exception {
            String aliceToken = SampleTokens.valid("alice", "USER");
            String adminToken = SampleTokens.valid("root", "USER", "ADMIN");

            assertThat(status(as(aliceToken, sub("a", method, url, null)), "a")).isEqualTo(alice);
            SampleController.reset();
            assertThat(status(as(adminToken, sub("a", method, url, null)), "a")).isEqualTo(admin);
        }

        @Test
        void deniedHandlers_doNotExecute() throws Exception {
            as(SampleTokens.valid("alice", "USER"),
                get("account", "/api/accounts/bob"),
                sub("delete", "DELETE", "/api/orders/2", null),
                get("audit", "/api/audit"),
                get("report", "/api/admin/report"));

            assertThat(SampleController.EXECUTED).isEmpty();
            assertThat(SampleController.orderExists(2)).isTrue();
        }

        @Test
        void postFilter_returnsOnlyTheCallersRows() throws Exception {
            JsonNode response = as(SampleTokens.valid("alice", "USER"), get("orders", "/api/orders"));

            assertThat(response.at("/responses/orders/body")).hasSize(1);
            assertThat(response.at("/responses/orders/body/0/owner").asText()).isEqualTo("alice");
        }

        @Test
        void jwtScopes_areEnforced() throws Exception {
            JsonNode allowed = as(SampleTokens.withScope("alice", "orders.read", "USER"), get("a", "/api/scoped"));
            JsonNode denied = as(SampleTokens.withScope("alice", "orders.write", "USER"), get("a", "/api/scoped"));

            assertThat(status(allowed, "a")).isEqualTo(200);
            assertThat(status(denied, "a")).isEqualTo(403);
        }

        @Test
        void orderOwner_comesFromTheToken_notTheRequestBody() throws Exception {
            String spoofed = sub("w", "POST", "/api/orders", "\"body\":{\"item\":\"x\",\"owner\":\"root\"}");

            JsonNode response = as(SampleTokens.valid("alice", "USER"), spoofed);

            assertThat(response.at("/responses/w/body/owner").asText()).isEqualTo("alice");
        }

        @Test
        void deniedDependency_blocksDependentWrite() throws Exception {
            JsonNode response = as(SampleTokens.valid("alice", "USER"),
                get("secret", "/api/orders/2"),
                sub("copy", "POST", "/api/orders", "\"body\":{\"item\":\"${secret.item}\"}"));

            assertThat(status(response, "secret")).isEqualTo(403);
            assertThat(status(response, "copy")).isEqualTo(424);
            assertThat(SampleController.ran("createOrder")).isFalse();
            assertThat(response.toString()).doesNotContain("bob-order");
        }

        @Test
        void stopOnFailure_stopsAfterAuthorizationFailure() throws Exception {
            String body = "{\"stopOnFailure\":true,\"subRequests\":["
                + get("denied", "/api/admin/report") + ","
                + get("slow", "/api/slow/700") + ","
                + sub("after", "POST", "/api/orders", "\"body\":{\"item\":\"${slow.id}\"}") + "]}";

            JsonNode response = ok(post(rest, EXECUTE, bearer(SampleTokens.valid("alice", "USER")), body));
            Thread.sleep(900);

            assertThat(status(response, "denied")).isEqualTo(403);
            assertThat(status(response, "after")).isEqualTo(424);
            assertThat(SampleController.ran("createOrder")).isFalse();
        }

        @Test
        void tokenExpiringDuringTheBatch_failsLaterSubRequests() throws Exception {
            String token = SampleTokens.expiringIn(Duration.ofMillis(1500), "alice", "USER");

            JsonNode response = as(token,
                get("slow", "/api/slow/2000"),
                sub("after", "POST", "/api/orders", "\"body\":{\"item\":\"${slow.id}\"}"));

            assertThat(status(response, "slow")).isEqualTo(200);
            assertThat(status(response, "after")).isEqualTo(401);
            assertThat(SampleController.ran("createOrder")).isFalse();
        }

        @Test
        void concurrentUsers_neverSeeEachOthersIdentity() throws Exception {
            List<String> users = List.of("alice", "bob", "root");
            ExecutorService executor = Executors.newFixedThreadPool(24);
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                String user = users.get(i % users.size());
                calls.add(() -> {
                    JsonNode response = as(SampleTokens.valid(user, "USER"),
                        get("a", "/api/me"), get("b", "/api/me"), get("c", "/api/me"));
                    return Stream.of("a", "b", "c")
                        .map(id -> response.at("/responses/" + id + "/body/name").asText())
                        .allMatch(user::equals) ? "ok" : "mixed for " + user + ": " + response;
                });
            }

            List<String> results = new ArrayList<>();
            for (Future<String> result : executor.invokeAll(calls)) {
                results.add(result.get());
            }
            executor.shutdown();

            assertThat(results).containsOnly("ok").hasSize(120);
        }

        @ParameterizedTest
        @ValueSource(strings = {"Authorization", "Cookie", "X-Forwarded-User", "X-Forwarded-For", "Forwarded", "X-Real-IP"})
        void clientCannotSetIdentityHeadersInsideABatch(String header) {
            String subRequest = sub("a", "GET", "/api/admin/report",
                "\"headers\":{\"" + header + "\":\"Bearer " + SampleTokens.valid("root", "ADMIN") + "\"}");

            ResponseEntity<String> response = post(rest, EXECUTE,
                bearer(SampleTokens.valid("alice", "USER")), batch(subRequest));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/api/internal/metrics",
            "/api/public/../internal/metrics",
            "/api/public/%2e%2e/internal/metrics",
            "/api/internal/metrics;x=1",
            "/api/orders/1?/../internal/metrics#x"
        })
        void unannotatedOrMalformedTargets_areUnreachableEvenForAdmin(String url) {
            post(rest, EXECUTE, bearer(SampleTokens.valid("root", "USER", "ADMIN")), batch(get("a", url)));

            assertThat(SampleController.ran("internalMetrics")).isFalse();
        }

        @Test
        void dispatchHeader_grantsNothingOnDirectRequests() {
            HttpHeaders anonymous = new HttpHeaders();
            anonymous.set("X-Composite-Dispatch", "true");
            HttpHeaders authenticated = bearer(SampleTokens.valid("alice", "USER"));
            authenticated.set("X-Composite-Dispatch", "true");

            ResponseEntity<String> protectedCall = rest.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(anonymous), String.class);
            ResponseEntity<String> adminCall = rest.exchange("/api/admin/report", HttpMethod.GET,
                new HttpEntity<>(authenticated), String.class);

            assertThat(protectedCall.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(adminCall.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void subResponseHeaders_doNotReachTheClient() {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer(SampleTokens.valid("alice", "USER")),
                batch(get("a", "/api/set-cookie")));

            assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(response.getHeaders().getFirst("X-Sub-Request-Secret")).isNull();
        }

        @Test
        void securityResponseHeaders_survive() {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer(SampleTokens.valid("alice", "USER")),
                batch(get("a", "/api/me")));

            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        }

        @Test
        void corsRules_applyToTheCompositeEndpoint() {
            HttpHeaders allowed = bearer(SampleTokens.valid("alice", "USER"));
            allowed.setOrigin(SampleSecurityConfiguration.ALLOWED_ORIGIN);
            HttpHeaders rejected = bearer(SampleTokens.valid("alice", "USER"));
            rejected.setOrigin("https://evil.example");
            HttpHeaders preflight = new HttpHeaders();
            preflight.setOrigin("https://evil.example");
            preflight.setAccessControlRequestMethod(HttpMethod.POST);

            ResponseEntity<String> allowedResponse = post(rest, EXECUTE, allowed, batch(get("a", "/api/me")));
            ResponseEntity<String> rejectedResponse = post(rest, EXECUTE, rejected, batch(createOrder("w", "x")));
            ResponseEntity<String> preflightResponse = rest.exchange(EXECUTE, HttpMethod.OPTIONS,
                new HttpEntity<>(preflight), String.class);

            assertThat(allowedResponse.getHeaders().get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .containsExactly(SampleSecurityConfiguration.ALLOWED_ORIGIN);
            assertThat(rejectedResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(preflightResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SampleController.ran("createOrder")).isFalse();
        }

        @Test
        void nonJsonContentType_isRejectedWithoutSideEffects() {
            HttpHeaders headers = bearer(SampleTokens.valid("alice", "USER"));
            headers.setContentType(MediaType.TEXT_PLAIN);

            ResponseEntity<String> response = rest.exchange(EXECUTE, HttpMethod.POST,
                new HttpEntity<>(batch(createOrder("w", "x")), headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(SampleController.EXECUTED).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "sample.security.mode=token")
    class HandWrittenTokenFilter {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SampleController.reset();
        }

        @Test
        void asyncResponse_keepsTheCallerAuthenticated() throws Exception {
            JsonNode response = ok(post(rest, EXECUTE, bearer("alice-token"),
                batch(get("me", "/api/me"), get("denied", "/api/admin/report"))));

            assertThat(response.at("/responses/me/body/name").asText()).isEqualTo("alice");
            assertThat(status(response, "denied")).isEqualTo(403);
        }

        @Test
        void validationFailure_stillReturns400ForAuthenticatedCaller() {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer("alice-token"),
                batch(get("a", "/api/internal/metrics")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void invalidToken_isRejected() {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer("wrong"), batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SampleController.EXECUTED).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "sample.security.mode=basic")
    class HttpBasic {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SampleController.reset();
        }

        private HttpHeaders basic(String user, String password) {
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(user, password);
            return headers;
        }

        @Test
        void credentials_areForwardedAndAuthorizedPerSubRequest() throws Exception {
            JsonNode response = ok(post(rest, EXECUTE, basic("alice", SampleSecurityConfiguration.PASSWORD),
                batch(get("me", "/api/me"), get("denied", "/api/audit"))));

            assertThat(response.at("/responses/me/body/name").asText()).isEqualTo("alice");
            assertThat(status(response, "denied")).isEqualTo(403);
        }

        @Test
        void wrongPassword_isRejected() {
            ResponseEntity<String> response = post(rest, EXECUTE, basic("alice", "wrong"),
                batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SampleController.EXECUTED).isEmpty();
        }
    }

    /** Logs in through the real form endpoint and obtains a real CSRF token. */
    private static HttpHeaders loginWithCsrf(TestRestTemplate rest, String username) throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", SampleSecurityConfiguration.PASSWORD);
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> login = rest.postForEntity("/login", new HttpEntity<>(form, loginHeaders), String.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String session = login.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
            .map(cookie -> cookie.split(";", 2)[0])
            .filter(cookie -> cookie.startsWith("JSESSIONID="))
            .collect(Collectors.joining("; "));

        HttpHeaders csrfRequest = new HttpHeaders();
        csrfRequest.add(HttpHeaders.COOKIE, session);
        ResponseEntity<String> csrf = rest.exchange("/api/csrf", HttpMethod.GET,
            new HttpEntity<>(csrfRequest), String.class);
        String token = JSON.readTree(csrf.getBody()).get("token").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session + "; XSRF-TOKEN=" + token);
        headers.add("X-XSRF-TOKEN", token);
        return headers;
    }

    private static HttpHeaders copy(HttpHeaders headers) {
        HttpHeaders copy = new HttpHeaders();
        copy.addAll(headers);
        return copy;
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
        "sample.security.mode=session",
        "composite.security.forwarded-headers=Cookie,X-XSRF-TOKEN"
    })
    class SessionWithCsrf {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SampleController.reset();
        }

        @Test
        void validSessionAndCsrf_canReadAndWrite() throws Exception {
            JsonNode response = ok(post(rest, EXECUTE, loginWithCsrf(rest, "alice"),
                batch(get("me", "/api/me"), sub("w", "POST", "/api/orders", "\"body\":{\"item\":\"${me.name}\"}"))));

            assertThat(status(response, "w")).isEqualTo(200);
            assertThat(response.at("/responses/w/body/owner").asText()).isEqualTo("alice");
        }

        @Test
        void missingCsrfToken_rejectsTheBatch() throws Exception {
            HttpHeaders headers = loginWithCsrf(rest, "alice");
            headers.remove("X-XSRF-TOKEN");

            ResponseEntity<String> response = post(rest, EXECUTE, headers, batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void wrongCsrfToken_rejectsTheBatch() throws Exception {
            HttpHeaders headers = loginWithCsrf(rest, "alice");
            headers.set("X-XSRF-TOKEN", "wrong-token");

            ResponseEntity<String> response = post(rest, EXECUTE, headers, batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void methodSecurity_appliesToSessionUser() throws Exception {
            JsonNode response = ok(post(rest, EXECUTE, loginWithCsrf(rest, "alice"),
                batch(get("a", "/api/admin/report"), get("b", "/api/accounts/bob"))));

            assertThat(status(response, "a")).isEqualTo(403);
            assertThat(status(response, "b")).isEqualTo(403);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void loggedOutSession_cannotRunABatch() throws Exception {
            HttpHeaders headers = loginWithCsrf(rest, "alice");
            ResponseEntity<String> logout = rest.exchange("/logout", HttpMethod.POST,
                new HttpEntity<>(copy(headers)), String.class);
            assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            ResponseEntity<String> response = post(rest, EXECUTE, copy(headers), batch(createOrder("w", "x")));

            assertThat(response.getStatusCode().is4xxClientError()).isTrue();
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void twoSessions_keepTheirOwnIdentity() throws Exception {
            JsonNode alice = ok(post(rest, EXECUTE, loginWithCsrf(rest, "alice"), batch(get("me", "/api/me"))));
            JsonNode bob = ok(post(rest, EXECUTE, loginWithCsrf(rest, "bob"), batch(get("me", "/api/me"))));

            assertThat(alice.at("/responses/me/body/name").asText()).isEqualTo("alice");
            assertThat(bob.at("/responses/me/body/name").asText()).isEqualTo("bob");
        }

        @Test
        void batch_doesNotChangeTheSessionCookie() throws Exception {
            ResponseEntity<String> response = post(rest, EXECUTE, loginWithCsrf(rest, "alice"),
                batch(get("a", "/api/me"), get("b", "/api/set-cookie")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))
                .noneMatch(cookie -> cookie.startsWith("JSESSIONID") || cookie.startsWith("SUB_REQUEST_COOKIE"));
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
        "sample.security.mode=session",
        "composite.security.forwarded-headers=Cookie"
    })
    class SessionWithoutForwardedCsrf {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void writeSubRequest_failsClosed() throws Exception {
            SampleController.reset();

            JsonNode response = ok(post(rest, EXECUTE, loginWithCsrf(rest, "alice"),
                batch(get("read", "/api/me"), createOrder("w", "x"))));

            assertThat(status(response, "read")).isEqualTo(200);
            assertThat(status(response, "w")).isEqualTo(403);
            assertThat(SampleController.ran("createOrder")).isFalse();
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "sample.security.public-composite=true")
    class PublicCompositeEndpoint {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void publicBatchEndpoint_doesNotGrantAccessToProtectedHandlers() throws Exception {
            SampleController.reset();

            JsonNode response = ok(post(rest, EXECUTE, new HttpHeaders(), batch(
                get("public", "/api/public/status"),
                get("protected", "/api/me"),
                get("admin", "/api/admin/report"),
                createOrder("write", "x"))));

            assertThat(status(response, "public")).isEqualTo(200);
            assertThat(status(response, "protected")).isEqualTo(401);
            assertThat(status(response, "admin")).isEqualTo(401);
            assertThat(status(response, "write")).isEqualTo(401);
            assertThat(SampleController.EXECUTED).containsExactly("status:public");
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "composite.security.forwarded-headers=")
    class CredentialForwardingDisabled {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void subRequests_failClosed() throws Exception {
            SampleController.reset();

            JsonNode response = ok(post(rest, EXECUTE, bearer(SampleTokens.valid("root", "USER", "ADMIN")),
                batch(get("me", "/api/me"), get("public", "/api/public/status"))));

            assertThat(status(response, "me")).isEqualTo(401);
            assertThat(status(response, "public")).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = "composite.debug-enabled=true")
    class DebugOutput {

        @Autowired
        private TestRestTemplate rest;

        @Test
        void debugResponse_neverContainsTheCallersToken() throws Exception {
            SampleController.reset();
            String token = SampleTokens.valid("alice", "USER");

            ResponseEntity<String> response = post(rest, EXECUTE, bearer(token), batch(get("me", "/api/me")));

            assertThat(ok(response).has("debug")).isTrue();
            assertThat(response.getBody()).doesNotContain(token).doesNotContain("Authorization");
        }
    }

    @Nested
    @SpringBootTest(classes = SampleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @TestPropertySource(properties = {
        "composite.controller.enabled=false",
        "server.servlet.context-path=/shop"
    })
    class CustomController {

        private static final String CUSTOM = "/partner/batch";

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            SampleController.reset();
        }

        @Test
        void authorizedCaller_canRunABatch() throws Exception {
            JsonNode response = ok(post(rest, CUSTOM, bearer(SampleTokens.valid("root", "USER", "ADMIN")),
                batch(get("me", "/api/me"), get("report", "/api/admin/report"))));

            assertThat(response.at("/responses/me/body/name").asText()).isEqualTo("root");
            assertThat(status(response, "report")).isEqualTo(200);
        }

        @Test
        void controllerPreAuthorize_blocksTheWholeBatch() {
            ResponseEntity<String> response = post(rest, CUSTOM, bearer(SampleTokens.valid("alice", "USER")),
                batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void anonymousCaller_isRejected() {
            ResponseEntity<String> response = post(rest, CUSTOM, new HttpHeaders(), batch(createOrder("w", "x")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(SampleController.EXECUTED).isEmpty();
        }

        @Test
        void malformedJson_getsAClearError() throws Exception {
            ResponseEntity<String> response = post(rest, CUSTOM, bearer(SampleTokens.valid("root", "USER", "ADMIN")),
                "{not json");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(JSON.readTree(response.getBody()).at("/errors/0").asText())
                .contains("not valid composite JSON");
        }

        @Test
        void unknownField_getsAClearError() throws Exception {
            ResponseEntity<String> response = post(rest, CUSTOM, bearer(SampleTokens.valid("root", "USER", "ADMIN")),
                "{\"allOrNone\":true}");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(JSON.readTree(response.getBody()).at("/errors/0").asText())
                .contains("Unknown composite request field: allOrNone");
        }

        @Test
        void builtInRoute_isGone() {
            ResponseEntity<String> response = post(rest, EXECUTE, bearer(SampleTokens.valid("root", "USER", "ADMIN")),
                batch(get("me", "/api/me")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
