package io.github.nabilcarel.composite.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;

class LoopbackTrustTest {

    private final X509Certificate server = mock(X509Certificate.class);
    private final X509Certificate other = mock(X509Certificate.class);
    private final LoopbackTrust trust = new LoopbackTrust(List.of(server));

    @Test
    void pinnedCertificate_isAccepted() {
        assertThatCode(() -> trust.checkServerTrusted(new X509Certificate[] {server}, "RSA"))
            .doesNotThrowAnyException();
        assertThatCode(() -> trust.checkServerTrusted(new X509Certificate[] {server}, "RSA", mock(SSLEngine.class)))
            .doesNotThrowAnyException();
    }

    @Test
    void anyOtherCertificate_isRejected_evenWhenItsIssuerIsPinned() {
        assertThatThrownBy(() -> trust.checkServerTrusted(new X509Certificate[] {other, server}, "RSA"))
            .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> trust.checkServerTrusted(new X509Certificate[] {other}, "RSA", mock(SSLEngine.class)))
            .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> trust.checkServerTrusted(new X509Certificate[0], "RSA"))
            .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> trust.checkServerTrusted(null, "RSA"))
            .isInstanceOf(CertificateException.class);
    }

    @Test
    void clientCertificates_areNeverAccepted() {
        assertThatThrownBy(() -> trust.checkClientTrusted(new X509Certificate[] {server}, "RSA"))
            .isInstanceOf(CertificateException.class);
    }

    @Test
    void update_replacesThePin() throws Exception {
        trust.update(List.of(other));

        trust.checkServerTrusted(new X509Certificate[] {other}, "RSA");
        assertThatThrownBy(() -> trust.checkServerTrusted(new X509Certificate[] {server}, "RSA"))
            .isInstanceOf(CertificateException.class);
        assertThat(trust.getAcceptedIssuers()).containsExactly(other);
    }

    @Test
    void emptyPin_isRefused() {
        assertThatThrownBy(() -> new LoopbackTrust(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trust.update(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
