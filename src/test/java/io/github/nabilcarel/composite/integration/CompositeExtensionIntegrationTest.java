package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.dispatch.CancellationSignal;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Verifies that the public dispatcher extension point fully replaces the default client. */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(CompositeExtensionIntegrationTest.DispatcherConfiguration.class)
@TestPropertySource(properties = "composite.request-timeout=700ms")
class CompositeExtensionIntegrationTest {

    private static final List<PreparedSubRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicInteger REFUSED = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ConfigurableApplicationContext context;
    @Autowired
    private ActiveBatches activeBatches;

    @TestConfiguration
    static class DispatcherConfiguration {

        @Bean
        SubRequestDispatcher fakeDispatcher() {
            return new SubRequestDispatcher() {
                @Override
                public CompletionStage<DispatchResult> dispatch(PreparedSubRequest request,
                                                                CancellationSignal cancellation) {
                    REQUESTS.add(request);
                    if (request.url().contains("/refused-twice") && REFUSED.incrementAndGet() <= 2) {
                        return CompletableFuture.failedFuture(new java.net.ConnectException("refused"));
                    }
                    if (request.url().contains("/refused-always")) {
                        REFUSED.incrementAndGet();
                        return CompletableFuture.failedFuture(new java.net.ConnectException("refused"));
                    }
                    if (request.url().contains("/jdk-connect-timeout")) {
                        // The JDK client attaches a ConnectException as the cause.
                        java.net.http.HttpConnectTimeoutException timeout =
                            new java.net.http.HttpConnectTimeoutException("connect timed out");
                        timeout.initCause(new java.net.ConnectException("HTTP connect timed out"));
                        return CompletableFuture.failedFuture(timeout);
                    }
                    if (request.url().contains("/netty-connect-timeout")) {
                        return CompletableFuture.failedFuture(
                            new io.netty.channel.ConnectTimeoutException("connect timed out"));
                    }
                    if (request.url().contains("/failing")) {
                        return CompletableFuture.failedFuture(new IllegalStateException("internal-host:1234"));
                    }
                    if (request.url().contains("/hanging")) {
                        CompletableFuture<DispatchResult> never = new CompletableFuture<>();
                        cancellation.onCancel(() -> CANCELLED.set(true));
                        return never;
                    }
                    return CompletableFuture.completedFuture(
                        new DispatchResult(200, MediaType.APPLICATION_JSON, "{\"id\":\"from-custom-dispatcher\"}"));
                }
            };
        }
    }

    @BeforeEach
    void reset() {
        REQUESTS.clear();
        CANCELLED.set(false);
        REFUSED.set(0);
    }

    @Test
    void refusedConnection_isRetried() throws Exception {
        JsonNode response = new ObjectMapper().readTree(execute(get("a", "refused-twice")).getBody());

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        assertThat(REFUSED).hasValue(3);
    }

    @Test
    void refusedConnection_givesUpAfterThreeRetries() throws Exception {
        JsonNode response = new ObjectMapper().readTree(execute(get("a", "refused-always")).getBody());

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
        assertThat(response.at("/responses/a/body").asText()).contains("could not be reached");
        assertThat(REFUSED).hasValue(4);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"jdk-connect-timeout", "netty-connect-timeout"})
    void connectTimeouts_areNotRetried(String id) throws Exception {
        JsonNode response = new ObjectMapper().readTree(execute(get("a", id)).getBody());

        assertThat(REQUESTS).hasSize(1);
        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
        assertThat(response.at("/responses/a/body").asText()).contains("could not be reached");
    }

    @Test
    void otherFailures_areNotRetried() {
        execute(get("a", "failing"));

        assertThat(REQUESTS).hasSize(1);
    }

    private ResponseEntity<String> execute(String... subRequests) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("caller-token");
        return rest.postForEntity("/api/composite/execute",
            new HttpEntity<>("{\"subRequests\":[" + String.join(",", subRequests) + "]}", headers), String.class);
    }

    private static String get(String referenceId, String id) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"/api/sec/users/" + id + "\"}";
    }

    @Test
    void customDispatcher_receivesPreparedRequests_andResolvesReferences() throws Exception {
        JsonNode response = new ObjectMapper().readTree(execute(get("a", "1"), get("b", "${a.id}")).getBody());

        assertThat(response.at("/responses/b/body/id").asText()).isEqualTo("from-custom-dispatcher");
        assertThat(REQUESTS).hasSize(2);
        PreparedSubRequest dependent = REQUESTS.stream()
            .filter(request -> request.referenceId().equals("b")).findFirst().orElseThrow();
        assertThat(dependent.method()).isEqualTo(HttpMethod.GET);
        assertThat(dependent.url()).isEqualTo("/api/sec/users/from-custom-dispatcher");
        assertThat(dependent.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer caller-token");
        assertThat(dependent.headers().getFirst("X-Composite-Dispatch")).hasSizeGreaterThan(20);
    }

    @Test
    void defaultWebClient_isNotCreated() {
        execute(get("a", "1"));

        assertThat(context.getBeanFactory().containsSingleton("compositeWebClient")).isFalse();
    }

    @Test
    void dispatcherFailure_isSafeForTheClient() throws Exception {
        JsonNode response = new ObjectMapper().readTree(execute(get("a", "failing")).getBody());

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
        assertThat(response.toString()).doesNotContain("internal-host");
    }

    @Test
    void timeout_signalsCancellationToTheDispatcher() {
        ResponseEntity<String> response = execute(get("a", "hanging"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(CANCELLED).isTrue();
        assertThat(activeBatches.count()).isZero();
    }
}
