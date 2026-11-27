package io.github.nabilcarel.composite.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackAddressTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0"})
    void unboundOrWildcardAddress_usesLocalhost(String serverAddress) {
        assertThat(LoopbackAddress.host(serverAddress)).isEqualTo("localhost");
    }

    @Test
    void ipv4Address_isUsedAsIs() {
        assertThat(LoopbackAddress.host("10.0.0.5")).isEqualTo("10.0.0.5");
        assertThat(LoopbackAddress.host(" 127.0.0.2 ")).isEqualTo("127.0.0.2");
    }

    @Test
    void ipv6Address_isBracketed() {
        assertThat(LoopbackAddress.host("::1")).isEqualTo("[::1]");
        assertThat(LoopbackAddress.host("[::1]")).isEqualTo("[::1]");
    }
}
