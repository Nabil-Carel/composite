package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.internal.LoopbackTrust;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs loopback calls over real TLS with generated self-signed certificates. Each loopback call
 * must trust exactly the server's own certificate.
 */
class CompositeHttpsIntegrationTest {

    private static final String PASSWORD = "changeit";
    private static final Path DIRECTORY = createDirectory();
    /** The usual development certificate, valid for localhost. */
    private static final Path LOCALHOST = keystore("localhost", "CN=localhost", "SAN=dns:localhost");
    /** Issued for another name, as a production certificate would be. */
    private static final Path PUBLIC_NAME = keystore("public", "CN=api.example.com", "SAN=dns:api.example.com");
    /** Valid for localhost but not this server's certificate. */
    private static final Path FOREIGN = keystore("foreign", "CN=localhost", "SAN=dns:localhost");
    /** The localhost certificate alone, in PEM, as a trust store would list it. */
    private static final Path LOCALHOST_PEM = exportCertificate(LOCALHOST);

    private static Path createDirectory() {
        try {
            Path directory = Files.createTempDirectory("composite-tls");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test directory", e);
        }
    }

    private static Path keystore(String name, String dname, String san) {
        Path keystore = DIRECTORY.resolve(name + ".p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", PASSWORD,
            "-keypass", PASSWORD, "-dname", dname, "-ext", san);
        return keystore;
    }

    private static Path exportCertificate(Path keystore) {
        Path pem = DIRECTORY.resolve("localhost.crt");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", keystore.toString(),
            "-storepass", PASSWORD, "-file", pem.toString());
        return pem;
    }

    private static void keytool(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + command);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test certificates", e);
        }
    }

    private static void serveWith(DynamicPropertyRegistry registry, Path keystore) {
        registry.add("server.ssl.enabled", () -> "true");
        registry.add("server.ssl.key-store", () -> keystore.toUri().toString());
        registry.add("server.ssl.key-store-type", () -> "PKCS12");
        registry.add("server.ssl.key-store-password", () -> PASSWORD);
        registry.add("server.ssl.key-alias", () -> "server");
        registry.add("composite.loopback.protocol", () -> "https");
    }

    /** The test client trusts only the server's certificate. Library trust is what is under test. */
    private static JsonNode execute(int port, Path serverKeystore) throws Exception {
        return execute(port, clientTrusting(serverKeystore));
    }

    private static JsonNode execute(int port, SSLContext sslContext) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(sslContext).build();
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

    private static void assertSucceeds(JsonNode response) {
        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
    }

    @Nested
    @SpringBootTest(classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class ServerCertificate {

        @LocalServerPort
        private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            serveWith(registry, LOCALHOST);
        }

        @Test
        void selfSignedServerCertificate_isTrustedWithoutConfiguration() throws Exception {
            assertSucceeds(execute(port, LOCALHOST));
        }
    }

    @Nested
    @SpringBootTest(classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class CertificateForAnotherHostName {

        @LocalServerPort
        private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            serveWith(registry, PUBLIC_NAME);
        }

        /** The pin identifies the server, so a certificate for a public name still serves localhost. */
        @Test
        void pinnedCertificate_needsNoMatchingHostName() throws Exception {
            // The test client also connects to localhost, so it pins the certificate the same way.
            KeyStore keys = load(PUBLIC_NAME);
            SSLContext pinned = new LoopbackTrust(List.of((X509Certificate) keys.getCertificate("server")))
                .sslContext();

            assertSucceeds(execute(port, pinned));
        }
    }

    @Nested
    @SpringBootTest(classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "composite.loopback.ssl-bundle=loopback")
    class BundleWithTheServerCertificate {

        @LocalServerPort
        private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            serveWith(registry, LOCALHOST);
            registry.add("spring.ssl.bundle.pem.loopback.truststore.certificate",
                () -> LOCALHOST_PEM.toUri().toString());
        }

        @Test
        void bundleTrustStore_listsTheCertificateToTrust() throws Exception {
            assertSucceeds(execute(port, LOCALHOST));
        }
    }

    @Nested
    @SpringBootTest(classes = CompositeIntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "composite.loopback.ssl-bundle=foreign")
    class BundleWithAForeignCertificate {

        @LocalServerPort
        private int port;

        @DynamicPropertySource
        static void properties(DynamicPropertyRegistry registry) {
            serveWith(registry, LOCALHOST);
            registry.add("spring.ssl.bundle.jks.foreign.keystore.location", () -> FOREIGN.toUri().toString());
            registry.add("spring.ssl.bundle.jks.foreign.keystore.type", () -> "PKCS12");
            registry.add("spring.ssl.bundle.jks.foreign.keystore.password", () -> PASSWORD);
        }

        /** Valid for localhost, but not the pinned certificate, so the call is refused. */
        @Test
        void anyOtherCertificate_failsTheSubRequest() throws Exception {
            JsonNode response = execute(port, LOCALHOST);

            assertThat(response.get("hasErrors").asBoolean()).isTrue();
            assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
            assertThat(response.at("/responses/a/body").asText())
                .isEqualTo("Error executing subrequest: the endpoint could not be reached.")
                .doesNotContain("localhost", String.valueOf(port));
        }
    }

    private static KeyStore load(Path keystore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, PASSWORD.toCharArray());
        }
        return keys;
    }

    private static SSLContext clientTrusting(Path serverKeystore) throws Exception {
        KeyStore serverKeys = load(serverKeystore);
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
