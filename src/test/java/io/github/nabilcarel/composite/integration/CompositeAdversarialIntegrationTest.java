package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.nabilcarel.composite.integration.controllers.TestAdversarialController;
import io.github.nabilcarel.composite.model.ResponseTracker;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Attacks the composite endpoint through the real HTTP stack. */
class CompositeAdversarialIntegrationTest {

    private static final String EXECUTE = "/api/composite/execute";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String get(String referenceId, String url) {
        return sub(referenceId, "GET", url, null);
    }

    private static String sub(String referenceId, String method, String url, String extra) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"" + method + "\",\"url\":\"" + url + "\""
            + (extra == null ? "" : "," + extra) + "}";
    }

    private static String write(String referenceId, String body) {
        return sub(referenceId, "POST", "/api/adv/write", "\"body\":" + body);
    }

    private static String batch(String... subRequests) {
        return "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, HttpHeaders headers, String body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(EXECUTE, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static ResponseEntity<String> post(TestRestTemplate rest, String body) {
        return post(rest, new HttpHeaders(), body);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody());
    }

    private static int status(JsonNode response, String referenceId) {
        return response.at("/responses/" + referenceId + "/httpStatus").asInt();
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "composite.security.forwarded-headers=Authorization,Cookie,X-API-Key",
        "composite.security.client-allowed-headers=X-Trace,X-User-Id,Accept-Language",
        "composite.security.blocked-headers=X-User-Id"
    })
    class HeaderForwarding {

        @Autowired
        private TestRestTemplate rest;

        @BeforeEach
        void reset() {
            TestAdversarialController.reset();
        }

        @Test
        void configuredCustomHeader_isForwarded() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", "key-1");

            post(rest, headers, batch(get("a", "/api/adv/headers")));

            assertThat(TestAdversarialController.lastHeaders.get("X-API-Key")).containsExactly("key-1");
        }

        @Test
        void unconfiguredOuterHeader_isNotForwarded() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Not-Forwarded", "secret");

            post(rest, headers, batch(get("a", "/api/adv/headers")));

            assertThat(TestAdversarialController.lastHeaders.containsKey("X-Not-Forwarded")).isFalse();
        }

        @Test
        void absentForwardedHeader_isNotInvented() {
            post(rest, batch(get("a", "/api/adv/headers")));

            assertThat(TestAdversarialController.lastHeaders.containsKey(HttpHeaders.AUTHORIZATION)).isFalse();
            assertThat(TestAdversarialController.lastHeaders.containsKey("X-API-Key")).isFalse();
        }

        @Test
        void repeatedForwardedHeader_keepsEveryValue() {
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-API-Key", "first");
            headers.add("X-API-Key", "second");

            post(rest, headers, batch(get("a", "/api/adv/headers")));

            assertThat(TestAdversarialController.lastHeaders.get("X-API-Key")).containsExactly("first", "second");
        }

        @Test
        void forwardedHeaders_reachDependentSubRequests() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-Key", "key-1");

            post(rest, headers, batch(
                get("v", "/api/adv/values"),
                sub("a", "GET", "/api/adv/headers", "\"headers\":{\"X-Trace\":\"${v.sql}\"}")));

            assertThat(TestAdversarialController.lastHeaders.getFirst("X-API-Key")).isEqualTo("key-1");
        }

        @ParameterizedTest
        @ValueSource(strings = {"X-User-Id", "x-user-id", "AUTHORIZATION", "cookie", "x-FORWARDED-user", "X-API-Key"})
        void blockedOrUnlistedHeaders_rejectTheBatchInAnyCase(String header) {
            ResponseEntity<String> response = post(rest, batch(
                sub("a", "GET", "/api/adv/headers", "\"headers\":{\"" + header + "\":\"spoofed\"}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.lastHeaders).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"crlf", "lf"})
        void storedNewlines_cannotInjectAHeader(String field) {
            post(rest, batch(
                get("v", "/api/adv/values"),
                sub("a", "GET", "/api/adv/headers", "\"headers\":{\"X-Trace\":\"${v." + field + "}\"}")));

            assertThat(TestAdversarialController.lastHeaders.containsKey("X-Injected")).isFalse();
            assertThat(TestAdversarialController.lastHeaders.getFirst("X-Trace")).doesNotContain("\r", "\n");
        }

        @Test
        void literalNewlines_cannotInjectAHeader() {
            post(rest, batch(sub("a", "GET", "/api/adv/headers",
                "\"headers\":{\"X-Trace\":\"safe\\r\\nX-Injected: yes\"}")));

            assertThat(TestAdversarialController.lastHeaders.containsKey("X-Injected")).isFalse();
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class Attacks {

        @Autowired
        private TestRestTemplate rest;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @BeforeEach
        void reset() {
            TestAdversarialController.reset();
        }

        // ========== Access without composite ==========

        @Test
        void directCall_toAnnotatedEndpoint_works() {
            assertThat(rest.getForEntity("/api/adv/items/1", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        void directCall_toUnannotatedEndpoint_isUnaffected() {
            assertThat(rest.getForEntity("/api/adv/hidden", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(TestAdversarialController.HIDDEN_CALLS).hasValue(1);
        }

        @Test
        void directCall_withDispatchHeader_cannotReachUnannotatedHandler() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Composite-Dispatch", "true");

            ResponseEntity<String> response = rest.exchange("/api/adv/hidden", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(TestAdversarialController.HIDDEN_CALLS).hasValue(0);
        }

        // ========== URL and encoding attacks ==========

        @ParameterizedTest
        @ValueSource(strings = {
            "/api/adv/hidden",
            "/api/adv/items/hidden",
            "/api/adv/items/%68idden",
            "/api/adv/items/%252e%252e/hidden",
            "/api/adv/items/%00",
            "/api/adv/items/%c0%ae%c0%ae/hidden",
            "/api/adv/items/%ef%bc%8e%ef%bc%8e/hidden",
            "/api/adv/items/..%5chidden",
            "/api/adv/items/..%2fhidden",
            "/api/adv/./hidden",
            "/api/adv/items/../hidden",
            "/api/adv//hidden",
            "/API/adv/hidden",
            "/api/adv/hidden/",
            "/api/adv/hidden;a=b",
            "/api/adv/items/1?x=/../hidden"
        })
        void encodedTargets_cannotReachUnannotatedHandlers(String url) {
            post(rest, batch(get("a", url)));

            assertThat(TestAdversarialController.HIDDEN_CALLS).hasValue(0);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "hidden", "dots", "encodedDots", "doubleEncoded", "encodedSlash", "backslash", "fullwidth",
            "overlong", "nul", "crlf", "lf", "template", "spel", "sql", "html", "query"
        })
        void hostileValues_inPath_cannotChangeTheRoute(String field) throws Exception {
            JsonNode response = json(post(rest, batch(
                get("v", "/api/adv/values"),
                get("a", "/api/adv/items/${v." + field + "}"))));

            assertThat(TestAdversarialController.HIDDEN_CALLS).hasValue(0);
            assertThat(status(response, "a")).isIn(200, 400);
            if (status(response, "a") == 200) {
                assertThat(TestAdversarialController.lastId)
                    .isEqualTo(response.at("/responses/v/body/" + field).asText());
            }
        }

        @Test
        void hostileValue_inQuery_cannotAddParameters() throws Exception {
            JsonNode response = json(post(rest, batch(
                get("v", "/api/adv/values"),
                get("a", "/api/adv/items/1?name=${v.query}"))));

            assertThat(status(response, "a")).isEqualTo(200);
            assertThat(TestAdversarialController.lastId).isEqualTo("1");
        }

        @ParameterizedTest
        @ValueSource(strings = {"template", "spel", "sql", "html"})
        void hostileValues_inBody_stayLiteralJson(String field) throws Exception {
            post(rest, batch(
                get("v", "/api/adv/values"),
                write("w", "{\"copy\":\"${v." + field + "}\",\"mixed\":\"x ${v." + field + "}\"}")));

            JsonNode written = JSON.readTree(TestAdversarialController.lastWrite);
            String expected = json(post(rest, batch(get("v", "/api/adv/values")))).at("/responses/v/body/" + field).asText();
            assertThat(written.get("copy").asText()).isEqualTo(expected);
            assertThat(written.get("mixed").asText()).isEqualTo("x " + expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"__proto__", "class", "getClass", "constructor", "crlf.bytes", "hidden[0]"})
        void referencePaths_cannotReachOutsideTheJson(String path) throws Exception {
            JsonNode response = json(post(rest, batch(
                get("v", "/api/adv/values"),
                write("w", "{\"x\":\"${v." + path + "}\"}"))));

            assertThat(status(response, "w")).isEqualTo(400);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        // ========== Redirects and recursion ==========

        @Test
        void redirectFromHandler_isNotFollowed() throws Exception {
            List<String> outside = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                outside.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            try {
                TestAdversarialController.redirectTarget =
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/steal";
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth("victim-token");

                JsonNode response = json(post(rest, headers, batch(get("a", "/api/adv/redirect"))));

                assertThat(status(response, "a")).isEqualTo(302);
                assertThat(response.get("hasErrors").asBoolean()).isTrue();
                assertThat(outside).isEmpty();
            } finally {
                server.stop(0);
            }
        }

        @Test
        void compositeEndpoint_cannotCallItself() {
            ResponseEntity<String> response = post(rest, batch(
                sub("a", "POST", EXECUTE, "\"body\":{\"subRequests\":[{\"referenceId\":\"x\",\"method\":\"GET\","
                    + "\"url\":\"/api/adv/items/1\"}]}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.lastId).isNull();
        }

        // ========== Malicious payloads ==========

        @ParameterizedTest
        @ValueSource(strings = {"TRACE", "HEAD", "OPTIONS", "CONNECT", "get", "G ET"})
        void unsupportedMethods_areRejected(String method) {
            ResponseEntity<String> response = post(rest, batch(sub("a", method, "/api/adv/items/1", null)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.lastId).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override"})
        void methodOverrideHeaders_areRejected(String header) {
            ResponseEntity<String> response = post(rest, batch(
                sub("a", "GET", "/api/adv/items/1", "\"headers\":{\"" + header + "\":\"DELETE\"}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void batchOverTheLimit_isRejectedBeforeAnythingRuns() {
            String[] subRequests = IntStream.range(0, 26)
                .mapToObj(i -> write("w" + i, "{\"x\":1}")).toArray(String[]::new);

            ResponseEntity<String> response = post(rest, batch(subRequests));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        @Test
        void dependencyChainOverTheLimit_isRejected() {
            List<String> chain = new ArrayList<>();
            chain.add(get("r0", "/api/adv/items/1"));
            for (int i = 1; i <= 12; i++) {
                chain.add(get("r" + i, "/api/adv/items/${r" + (i - 1) + ".id}"));
            }

            ResponseEntity<String> response = post(rest, batch(chain.toArray(String[]::new)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.lastId).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/adv/items/${a.id}\"}]}",
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/adv/items/${b.id}\"},"
                + "{\"referenceId\":\"b\",\"method\":\"GET\",\"url\":\"/api/adv/items/${a.id}\"}]}",
            "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/adv/items/1\"},"
                + "{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/adv/items/2\"}]}",
            "{\"subRequests\":[{\"referenceId\":\"../a\",\"method\":\"GET\",\"url\":\"/api/adv/items/1\"}]}"
        })
        void invalidGraphs_areRejected(String body) {
            ResponseEntity<String> response = post(rest, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.lastId).isNull();
        }

        @Test
        void excessivelyDeepJson_isRejectedCleanly() {
            int depth = 5000;
            String nested = "[".repeat(depth) + "]".repeat(depth);

            ResponseEntity<String> response = post(rest, batch(write("w", nested)));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        @Test
        void deepButAllowedJson_doesNotCrashTheServer() {
            int depth = 900;
            String nested = "[".repeat(depth) + "\"${v.sql}\"" + "]".repeat(depth);

            ResponseEntity<String> response = post(rest, batch(get("v", "/api/adv/values"), write("w", nested)));

            assertThat(response.getStatusCode().is5xxServerError()).isFalse();
            assertThat(responseStore).isEmpty();
        }

        @Test
        void hugeStringInRequest_isRejectedCleanly() {
            String huge = "x".repeat(21 * 1024 * 1024);

            ResponseEntity<String> response = post(rest, batch(write("w", "{\"x\":\"" + huge + "\"}")));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        @Test
        void referenceAmplification_inBody_isRejected() throws Exception {
            String references = IntStream.range(0, 100).mapToObj(i -> "\"${big.value}\"")
                .collect(Collectors.joining(","));

            JsonNode response = json(post(rest, batch(get("big", "/api/adv/large"), write("w", "[" + references + "]"))));

            assertThat(status(response, "w")).isEqualTo(400);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        @Test
        void referenceAmplification_inText_isRejected() throws Exception {
            String references = "${big.value}".repeat(100);

            JsonNode response = json(post(rest, batch(
                get("big", "/api/adv/large"), write("w", "{\"x\":\"" + references + "\"}"))));

            assertThat(status(response, "w")).isEqualTo(400);
            assertThat(TestAdversarialController.WRITES).hasValue(0);
        }

        @Test
        void referenceAmplification_inUrl_isRejected() throws Exception {
            String references = "${big.value}".repeat(100);

            JsonNode response = json(post(rest, batch(
                get("big", "/api/adv/large"), get("a", "/api/adv/items/" + references))));

            assertThat(status(response, "a")).isEqualTo(400);
            assertThat(TestAdversarialController.lastId).isNull();
        }
    }

    private static long directCallMillis(HttpClient client, int port) throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/adv/fast"))
                .timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static HttpResponse<String> execute(HttpClient client, int port, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + EXECUTE))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String slowBatch(int size, long millis) {
        return batch(IntStream.range(0, size)
            .mapToObj(i -> get("s" + i, "/api/adv/slow/" + millis)).toArray(String[]::new));
    }

    private static void awaitActive(int minimum) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (TestAdversarialController.ACTIVE.get() < minimum && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "server.tomcat.threads.max=20")
    class ThreadSaturation {

        @LocalServerPort
        private int port;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @Test
        void normalTrafficStaysResponsive_andTheServerRecovers() throws Exception {
            TestAdversarialController.reset();
            HttpClient client = HttpClient.newHttpClient();
            ExecutorService executor = Executors.newFixedThreadPool(20);
            List<Future<HttpResponse<String>>> batches = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                batches.add(executor.submit(() -> execute(client, port, slowBatch(8, 400))));
            }
            awaitActive(5);

            List<Long> latencies = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                latencies.add(directCallMillis(client, port));
                Thread.sleep(50);
            }

            for (Future<HttpResponse<String>> batch : batches) {
                HttpResponse<String> response = batch.get();
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(response.body()).get("hasErrors").asBoolean()).isFalse();
            }
            executor.shutdown();

            assertThat(TestAdversarialController.MAX_ACTIVE.get()).isLessThanOrEqualTo(10);
            assertThat(latencies).allMatch(latency -> latency < 300);
            assertThat(responseStore).isEmpty();
            assertThat(execute(client, port, batch(get("a", "/api/adv/items/1"))).statusCode()).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = {
        "server.tomcat.threads.max=20",
        "composite.request-timeout=250ms"
    })
    class AbandonedSlowHandlers {

        @LocalServerPort
        private int port;
        @Autowired
        private ConcurrentMap<String, ResponseTracker> responseStore;

        @Test
        void repeatedTimeouts_cannotExhaustTheThreadPool() throws Exception {
            TestAdversarialController.reset();
            HttpClient client = HttpClient.newHttpClient();
            execute(client, port, batch(get("warm", "/api/adv/items/1")));

            ExecutorService executor = Executors.newFixedThreadPool(8);
            List<Future<HttpResponse<String>>> attacks = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                for (int i = 0; i < 8; i++) {
                    attacks.add(executor.submit(() -> execute(client, port, slowBatch(5, 1800))));
                }
                Thread.sleep(300);
            }

            long latency = directCallMillis(client, port);
            for (Future<HttpResponse<String>> attack : attacks) {
                attack.get();
            }
            executor.shutdown();

            assertThat(TestAdversarialController.MAX_ACTIVE.get()).isLessThanOrEqualTo(10);
            assertThat(latency).isLessThan(500);
            assertThat(responseStore).isEmpty();

            Thread.sleep(2000);
            assertThat(execute(client, port, batch(get("a", "/api/adv/items/1"))).statusCode()).isEqualTo(200);
        }
    }
}
