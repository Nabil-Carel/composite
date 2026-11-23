package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Runs loopback calls over real TLS with a generated self-signed certificate. */
class CompositeHttpsIntegrationTest {

    private static final String PASSWORD = "changeit";
    private static final Path KEYSTORE = generateKeystore();

    private static Path generateKeystore() {
        try {
            Path directory = Files.createTempDirectory("composite-tls");
            Path keystore = directory.resolve("server.p12");
            keystore.toFile().deleteOnExit();
            directory.toFile().deleteOnExit();
            String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD, "-dname", "CN=localhost", "-ext", "SAN=dns:localhost")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed");
            }
            return keystore;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test keystore", e);
        }
    }

    private static void tlsProperties(DynamicPropertyRegistry registry) {
        registry.add("server.ssl.enabled", () -> "true");
        registry.add("server.ssl.key-store", () -> KEYSTORE.toUri().toString());
        registry.add("server.ssl.key-store-type", () -> "PKCS12");
        registry.add("server.ssl.key-store-password", () -> PASSWORD);
        registry.add("server.ssl.key-alias", () -> "server");
        registry.add("composite.loopback.protocol", () -> "https");
    }

    /** The test client trusts the throwaway certificate. Library trust is what is under test. */
    private static JsonNode execute(int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new TrustAll()}, new SecureRandom());
        HttpClient client = HttpClient.newBuilder().sslContext(context).build();
        String body = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/sec/secret\"}]}";

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/composite/execute"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        return new ObjectMapper().readTree(response.body());
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    @TestPropertySource(properties = "composite.loopback.trust-self-signed-certificates=true")
    class TrustedLoopback {

        @LocalServerPort
        private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            tlsProperties(registry);
        }

        @Test
        void subRequest_runsOverHttps() throws Exception {
            assertThat(execute(port).at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(
        classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
    )
    class UntrustedLoopback {

        @LocalServerPort
        private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            tlsProperties(registry);
        }

        @Test
        void untrustedCertificate_failsTheSubRequest() throws Exception {
            JsonNode response = execute(port);

            assertThat(response.get("hasErrors").asBoolean()).isTrue();
            assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
        }
    }

    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
