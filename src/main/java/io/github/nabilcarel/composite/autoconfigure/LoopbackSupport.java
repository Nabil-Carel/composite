package io.github.nabilcarel.composite.autoconfigure;

import io.github.nabilcarel.composite.config.CompositeLoopbackProperties;
import io.github.nabilcarel.composite.internal.LoopbackTrust;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerSslBundle;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
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

    static boolean isHttps(CompositeLoopbackProperties loopbackProperties) {
        return "https".equalsIgnoreCase(loopbackProperties.getProtocol());
    }

    /**
     * Pins HTTPS loopback calls to the certificate of the application's own server.
     *
     * <p>The certificate comes from the bundle named by {@code composite.loopback.ssl-bundle}
     * when set, otherwise from the server's own {@code server.ssl} settings. When the server's
     * bundle is reloaded, the pin follows it.
     *
     * @param loopbackProperties loopback settings
     * @param serverSsl the server's SSL settings, or {@code null} when unavailable
     * @param bundles the application's SSL bundles, or {@code null} when unavailable
     * @return the trust manager, or {@code null} for plain HTTP, or when the server's certificate
     *     cannot be found, in which case the JVM truststore applies
     * @throws IllegalStateException if the configured bundle holds no usable certificate
     */
    static LoopbackTrust loopbackTrust(CompositeLoopbackProperties loopbackProperties, Ssl serverSsl,
                                       SslBundles bundles) {
        if (!isHttps(loopbackProperties)) {
            return null;
        }

        String bundleName = loopbackProperties.getSslBundle();
        if (StringUtils.hasText(bundleName)) {
            if (bundles == null) {
                throw new IllegalStateException(
                        "composite.loopback.ssl-bundle is set, but the application has no SSL bundles");
            }
            String source = "SSL bundle '" + bundleName + "'";
            LoopbackTrust trust = new LoopbackTrust(certificates(bundles.getBundle(bundleName), true, source));
            bundles.addBundleUpdateHandler(bundleName, bundle -> trust.update(certificates(bundle, true, source)));
            return trust;
        }

        if (!Ssl.isEnabled(serverSsl)) {
            log.warn("""
                    composite.loopback.protocol is https, but server.ssl is not configured, so loopback \
                    calls fall back to the JVM truststore. Configure server.ssl, name a bundle holding \
                    the server certificate with composite.loopback.ssl-bundle, or set \
                    composite.loopback.protocol=http.""");
            return null;
        }

        LoopbackTrust trust = new LoopbackTrust(
                certificates(WebServerSslBundle.get(serverSsl, bundles), false, "server.ssl"));
        if (StringUtils.hasText(serverSsl.getBundle()) && bundles != null) {
            bundles.addBundleUpdateHandler(serverSsl.getBundle(),
                    bundle -> trust.update(certificates(bundle, false, "server.ssl")));
        }
        return trust;
    }

    /**
     * Collects the certificates to pin. A bundle named for loopback calls may list them in its
     * trust store. The server's own trust store is never used, because it holds the
     * authorities for client certificates rather than the server's identity.
     */
    private static List<X509Certificate> certificates(SslBundle bundle, boolean useTrustStore, String source) {
        List<X509Certificate> certificates = new ArrayList<>();
        try {
            KeyStore trustStore = bundle.getStores().getTrustStore();
            if (useTrustStore && trustStore != null) {
                for (String alias : Collections.list(trustStore.aliases())) {
                    if (trustStore.isCertificateEntry(alias)
                            && trustStore.getCertificate(alias) instanceof X509Certificate certificate) {
                        certificates.add(certificate);
                    }
                }
            }

            KeyStore keyStore = bundle.getStores().getKeyStore();
            String keyAlias = bundle.getKey() != null ? bundle.getKey().getAlias() : null;
            if (certificates.isEmpty() && keyStore != null) {
                for (String alias : Collections.list(keyStore.aliases())) {
                    if ((keyAlias == null || keyAlias.equals(alias)) && keyStore.isKeyEntry(alias)
                            && keyStore.getCertificate(alias) instanceof X509Certificate certificate) {
                        certificates.add(certificate);
                    }
                }
            }
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Could not read the certificates in " + source, e);
        }

        if (certificates.isEmpty()) {
            throw new IllegalStateException(source + " contains no certificate for loopback calls to trust");
        }
        return certificates;
    }
}
