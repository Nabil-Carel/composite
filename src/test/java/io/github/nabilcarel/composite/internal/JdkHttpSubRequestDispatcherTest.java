package io.github.nabilcarel.composite.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * Proves that every way a call can end returns its connection permit. The dispatcher allows
 * one connection, so a leaked permit makes the next call wait forever.
 */
class JdkHttpSubRequestDispatcherTest {

    private static final long LIMIT = 64;

    private final AtomicInteger okHits = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger maxRunning = new AtomicInteger();
    private final AtomicReference<String> baseUrl = new AtomicReference<>();
    private CountDownLatch hangReached;
    private CountDownLatch releaseHang;
    private HttpServer server;
    private ExecutorService serverThreads;
    private JdkHttpSubRequestDispatcher dispatcher;

    @BeforeEach
    void start() throws IOException {
        hangReached = new CountDownLatch(1);
        releaseHang = new CountDownLatch(1);
        serverThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            respond(exchange, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), false);
        });
        server.createContext("/big", exchange -> respond(exchange, new byte[1000], false));
        server.createContext("/big-chunked", exchange -> respond(exchange, new byte[1000], true));
        server.createContext("/hang", exchange -> {
            hangReached.countDown();
            await(releaseHang);
            respond(exchange, new byte[0], false);
        });
        server.createContext("/late-body", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.flush();
                sleep(800);
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/late-headers", exchange -> {
            sleep(800);
            respond(exchange, new byte[0], false);
        });
        server.createContext("/slow", exchange -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            respond(exchange, new byte[0], false);
        });
        server.start();

        baseUrl.set("http://localhost:" + server.getAddress().getPort());
        dispatcher = new JdkHttpSubRequestDispatcher(baseUrl::get, new ObjectMapper(),
                Executors.newCachedThreadPool(), Duration.ofSeconds(2), null, LIMIT, 1, null);
    }

    @AfterEach
    void stop() {
        releaseHang.countDown();
        dispatcher.close();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void declaredOversizedResponse_failsAndReleasesThePermit() {
        assertFailsWith(send("/big"), ResponseTooLargeException.class);
        assertPermitAvailable();
    }

    @Test
    void chunkedOversizedResponse_failsAndReleasesThePermit() {
        assertFailsWith(send("/big-chunked"), ResponseTooLargeException.class);
        assertPermitAvailable();
    }

    @Test
    void refusedConnection_failsAndReleasesThePermit() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String live = baseUrl.getAndSet("http://localhost:" + closedPort);

        assertFailsWith(send("/ok"), ConnectException.class);
        assertFailsWith(send("/ok"), ConnectException.class);

        baseUrl.set(live);
        assertPermitAvailable();
    }

    @Test
    void requestThatCannotBeBuilt_failsAndReleasesThePermit() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Bad", "line\nbreak");

        assertFailsWith(dispatcher.dispatch(request("/ok", headers), new DefaultCancellationSignal())
                .toCompletableFuture(), IllegalArgumentException.class);
        assertPermitAvailable();
    }

    @Test
    void cancellingAnInFlightCall_releasesThePermit() throws Exception {
        DefaultCancellationSignal cancellation = new DefaultCancellationSignal();
        CompletableFuture<DispatchResult> hanging = dispatcher.dispatch(request("/hang"), cancellation)
                .toCompletableFuture();
        assertThat(hangReached.await(5, TimeUnit.SECONDS)).isTrue();

        cancellation.cancel();

        assertThat(hanging).isDone();
        assertPermitAvailable();
    }

    @Test
    void cancellingAQueuedCall_completesItAndNeverSendsIt() throws Exception {
        CompletableFuture<DispatchResult> hanging = send("/hang");
        assertThat(hangReached.await(5, TimeUnit.SECONDS)).isTrue();

        DefaultCancellationSignal cancellation = new DefaultCancellationSignal();
        CompletableFuture<DispatchResult> queued = dispatcher.dispatch(request("/ok"), cancellation)
                .toCompletableFuture();
        assertThat(queued).isNotDone();
        assertThat(dispatcher.waitingCount()).isEqualTo(1);

        cancellation.cancel();
        assertThat(queued).isCancelled();
        assertThat(dispatcher.waitingCount()).isZero();

        releaseHang.countDown();
        assertThat(hanging.get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertPermitAvailable();
        assertThat(okHits).hasValue(1);
    }

    /** composite.loopback.response-timeout limits the wait for headers, as documented, not the body. */
    @Test
    void responseTimeout_coversHeadersButNotTheBody() throws Exception {
        try (JdkHttpSubRequestDispatcher timed = new JdkHttpSubRequestDispatcher(baseUrl::get, new ObjectMapper(),
                Executors.newCachedThreadPool(), Duration.ofSeconds(2), Duration.ofMillis(300), LIMIT, 2, null)) {
            assertThat(timed.dispatch(request("/late-body"), new DefaultCancellationSignal())
                .toCompletableFuture().get(5, TimeUnit.SECONDS).body()).isEqualTo("{}");

            assertFailsWith(timed.dispatch(request("/late-headers"), new DefaultCancellationSignal())
                .toCompletableFuture(), java.net.http.HttpTimeoutException.class);
        }
    }

    @Test
    void connectionLimit_holdsUnderLoad() throws Exception {
        List<CompletableFuture<DispatchResult>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            calls.add(send("/slow"));
        }

        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

        assertThat(maxRunning).hasValue(1);
        assertPermitAvailable();
    }

    private CompletableFuture<DispatchResult> send(String path) {
        return dispatcher.dispatch(request(path), new DefaultCancellationSignal()).toCompletableFuture();
    }

    private void assertPermitAvailable() {
        try {
            assertThat(send("/ok").get(5, TimeUnit.SECONDS).status()).isEqualTo(200);
        } catch (Exception e) {
            fail("The next call did not complete, so a permit was not released", e);
        }
    }

    private static void assertFailsWith(CompletableFuture<?> call, Class<? extends Throwable> expected) {
        try {
            call.get(5, TimeUnit.SECONDS);
            fail("Expected " + expected.getSimpleName());
        } catch (ExecutionException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (expected.isInstance(cause)) {
                    return;
                }
            }
            fail("Expected " + expected.getSimpleName(), e);
        } catch (Exception e) {
            fail("Call did not fail in time", e);
        }
    }

    private static PreparedSubRequest request(String path) {
        return request(path, new HttpHeaders());
    }

    private static PreparedSubRequest request(String path, HttpHeaders headers) {
        return new PreparedSubRequest("a", HttpMethod.GET, path, headers, null);
    }

    private static void respond(HttpExchange exchange, byte[] body, boolean chunked) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, chunked ? 0 : (body.length == 0 ? -1 : body.length));
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
