package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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

    /**
     * The test client trusts only the throwaway certificate. Library trust is what is under test.
     */
    private static JsonNode execute(int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(clientTrustingTestCertificate()).build();
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
            assertThat(response.at("/responses/a/body").asText())
                .isEqualTo("Error executing subrequest: the endpoint could not be reached.")
                .doesNotContain("localhost", String.valueOf(port));
        }
    }

    private static SSLContext clientTrustingTestCertificate() throws Exception {
        KeyStore serverKeys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(KEYSTORE)) {
            serverKeys.load(in, PASSWORD.toCharArray());
        }
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("server", serverKeys.getCertificate("server"));

        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
