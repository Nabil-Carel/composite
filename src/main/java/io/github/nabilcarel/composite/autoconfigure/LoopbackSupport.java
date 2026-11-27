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
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/** Loopback settings shared by both HTTP clients and the handler limit. */
@Slf4j
final class LoopbackSupport {

    private LoopbackSupport() {
    }

    /**
     * Limits loopback calls by the resource that runs out first, leaving half of it for normal
     * traffic.
     *
     * <p>With platform threads, each running sub-request holds a Tomcat thread, so the default
     * is half of {@code server.tomcat.threads.max}. With virtual threads, threads are no longer
     * scarce but each call still holds a client and a server connection, so the default is half
     * of {@code server.tomcat.max-connections}.
     */
    static int effectiveMaxConnections(Environment environment, CompositeLoopbackProperties loopbackProperties) {
        boolean virtualThreads = Threading.VIRTUAL.isActive(environment);
        String limitProperty = virtualThreads ? "server.tomcat.max-connections" : "server.tomcat.threads.max";
        int defaultLimit = virtualThreads ? 8192 : 200;
        Integer configured = environment.getProperty(limitProperty, Integer.class, defaultLimit);
        int serverLimit = configured == null || configured < 1 ? defaultLimit : configured;

        if (loopbackProperties.getMaxConnections() == null) {
            return Math.max(1, serverLimit / 2);
        }

        if (loopbackProperties.getMaxConnections() >= serverLimit) {
            log.warn("composite.loopback.max-connections ({}) leaves no capacity in {} ({}) for normal traffic",
                    loopbackProperties.getMaxConnections(), limitProperty, serverLimit);
        }
        return loopbackProperties.getMaxConnections();
    }

    /**
     * Returns the server-side limit on running sub-request handlers, slightly above the
     * client's connection limit.
     *
     * <p>A handler gives its slot back only after the client has already read the whole
     * response, so with equal limits a queued call sent in that short gap would get a
     * spurious {@code 503}. The extra 10%, at least one, covers handlers that are finishing.
     */
    static int handlerLimit(int maxConnections) {
        return maxConnections + Math.max(1, maxConnections / 10);
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
     * Builds a TLS context that accepts any certificate and skips host name checks.
     *
     * <p><strong>Insecure, for development and tests only.</strong> Used only when
     * {@code composite.loopback.trust-self-signed-certificates=true}, which is disabled by
     * default, and only for HTTPS loopback calls to this application's own server. Anything
     * able to intercept local traffic could then read or change those calls, including the
     * forwarded credentials. {@link #warnIfTrustingAllCertificates} logs a warning at startup
     * whenever the option is on. In production, add the server certificate to the JVM
     * truststore instead.
     */
    static SSLContext trustAllSslContext() {
        // Deliberately accepts every certificate. See the warning above.
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

        // Trusting every certificate is warned about at startup by warnIfTrustingAllCertificates.
        if (!loopbackProperties.isTrustSelfSignedCertificates()) {
            log.info("""
                    Composite loopback using HTTPS with default truststore.
                    If using self-signed certificates, either:
                      - Import your certificate into the JVM truststore (recommended for production)
                      - Or set composite.loopback.trust-self-signed-certificates=true (dev/test only)""");
        }
    }

    /**
     * Logs a warning when {@code composite.loopback.trust-self-signed-certificates} is on. Runs
     * at startup because the HTTP clients are created on the first batch.
     *
     * @param environment the application environment
     * @return whether the option is on
     */
    static boolean warnIfTrustingAllCertificates(Environment environment) {
        Binder binder = Binder.get(environment);
        boolean trustAll = binder.bind("composite.loopback.trust-self-signed-certificates", Boolean.class)
                .orElse(false);
        if (!trustAll) {
            return false;
        }

        boolean https = "https".equalsIgnoreCase(
                binder.bind("composite.loopback.protocol", String.class).orElse("http"));
        if (https) {
            log.warn("""
                    composite.loopback.trust-self-signed-certificates is enabled: loopback calls accept \
                    any certificate and skip host name checks. This is insecure and meant for development \
                    and tests only. In production, add the server certificate to the JVM truststore and \
                    remove this setting.""");
        } else {
            log.warn("""
                    composite.loopback.trust-self-signed-certificates is enabled but has no effect, because \
                    composite.loopback.protocol is not https. Remove the setting. It is meant for \
                    development and tests only.""");
        }
        return true;
    }
}
