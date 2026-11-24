package io.github.nabilcarel.composite.autoconfigure;

import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/** Loopback settings shared by both HTTP clients and the handler limit. */
@Slf4j
final class LoopbackSupport {

    private LoopbackSupport() {
    }

    /** Defaults to half of Tomcat's thread pool, leaving the rest for normal traffic. */
    static int effectiveMaxConnections(Environment environment, CompositeLoopbackProperties loopbackProperties) {
        Integer configuredThreads = environment.getProperty("server.tomcat.threads.max", Integer.class, 200);
        int serverThreads = configuredThreads == null ? 200 : configuredThreads;

        if (loopbackProperties.getMaxConnections() == null) {
            return Math.max(1, serverThreads / 2);
        }

        if (loopbackProperties.getMaxConnections() >= serverThreads) {
            log.warn("composite.loopback.max-connections ({}) leaves no Tomcat threads for normal traffic ({})",
                    loopbackProperties.getMaxConnections(), serverThreads);
        }
        return loopbackProperties.getMaxConnections();
    }

    static int toBufferLimit(DataSize maxResponseSize) {
        if (maxResponseSize == null
                || maxResponseSize.toBytes() < 1
                || maxResponseSize.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "composite.loopback.max-response-size must be between 1B and " + Integer.MAX_VALUE + "B");
        }
        return (int) maxResponseSize.toBytes();
    }

    /**
     * Builds a TLS context that accepts any certificate and skips host name checks, for
     * {@code composite.loopback.trust-self-signed-certificates=true}.
     */
    static SSLContext trustAllSslContext() {
        X509ExtendedTrustManager trustAll = new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to configure SSL context for self-signed certificates", e);
        }
    }

    static boolean isHttps(CompositeLoopbackProperties loopbackProperties) {
        return "https".equalsIgnoreCase(loopbackProperties.getProtocol());
    }

    /** Explains HTTPS settings that will likely fail or weaken security. */
    static void logHttpsSetup(Environment environment, CompositeLoopbackProperties loopbackProperties) {
        if (!environment.getProperty("server.ssl.enabled", Boolean.class, false)) {
            log.warn("""
                    Composite loopback configured for HTTPS but server SSL is not enabled.
                    Either:
                      - Set server.ssl.enabled=true with a valid keystore
                      - Or change composite.loopback.protocol=http for unencrypted loopback
                    Attempting to proceed, but connections will likely fail.""");
        }

        if (loopbackProperties.isTrustSelfSignedCertificates()) {
            log.warn("Composite loopback is configured to trust self-signed certificates. "
                    + "This should only be used in development/testing environments.");
        } else {
            log.info("""
                    Composite loopback using HTTPS with default truststore.
                    If using self-signed certificates, either:
                      - Import your certificate into the JVM truststore (recommended for production)
                      - Or set composite.loopback.trust-self-signed-certificates=true (dev/test only)""");
        }
    }
}
