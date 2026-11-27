package io.github.nabilcarel.composite.internal;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Trusts exactly the certificates the application's own server presents, for HTTPS loopback
 * calls.
 *
 * <p>A connection is accepted only when the server's certificate is one of the pinned
 * certificates. Anyone presenting it must also hold its private key, so the pin alone proves
 * the call reached this application. Host names are therefore not checked, which lets a
 * certificate issued for a public name serve calls to {@code localhost}.
 *
 * <p>The pinned set can be replaced when the server's SSL bundle is reloaded.
 */
public final class LoopbackTrust extends X509ExtendedTrustManager {

    private volatile Set<X509Certificate> pinned;

    /**
     * Creates a trust manager pinned to the given certificates.
     *
     * @param certificates the server certificates to accept; must not be empty
     */
    public LoopbackTrust(Collection<X509Certificate> certificates) {
        update(certificates);
    }

    /**
     * Replaces the pinned certificates, for example after the server's bundle is reloaded.
     *
     * @param certificates the server certificates to accept; must not be empty
     */
    public void update(Collection<X509Certificate> certificates) {
        if (certificates == null || certificates.isEmpty()) {
            throw new IllegalArgumentException("At least one certificate must be pinned");
        }
        pinned = Set.copyOf(certificates);
    }

    /**
     * Builds a TLS context for the JDK client that uses only this trust manager.
     *
     * @return a new TLS context
     */
    public SSLContext sslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {this}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to configure TLS for loopback calls", e);
        }
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0 || !pinned.contains(chain[0])) {
            throw new CertificateException(
                    "The loopback server did not present this application's own certificate");
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        check(chain);
    }

    // The loopback client never acts as a server, so client certificates are never accepted.
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Loopback calls do not accept client certificates");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return pinned.toArray(new X509Certificate[0]);
    }
}
