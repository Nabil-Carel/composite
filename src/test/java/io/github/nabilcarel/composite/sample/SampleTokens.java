package io.github.nabilcarel.composite.sample;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Issues test JWTs. The keys exist only for the lifetime of the test JVM. */
final class SampleTokens {

    private static final KeyPair TRUSTED_KEYS = generateKeys();
    private static final KeyPair UNTRUSTED_KEYS = generateKeys();

    private SampleTokens() {
    }

    static RSAPublicKey publicKey() {
        return (RSAPublicKey) TRUSTED_KEYS.getPublic();
    }

    static String valid(String subject, String... roles) {
        return issue(subject, Duration.ofMinutes(5), "", roles);
    }

    static String withScope(String subject, String scope, String... roles) {
        return issue(subject, Duration.ofMinutes(5), scope, roles);
    }

    static String expiringIn(Duration lifetime, String subject, String... roles) {
        return issue(subject, lifetime, "", roles);
    }

    static String signedByUntrustedKey(String subject, String... roles) {
        return sign(claims(subject, Duration.ofMinutes(5), "", roles), UNTRUSTED_KEYS);
    }

    static String unsigned(String subject, String... roles) {
        return new PlainJWT(claims(subject, Duration.ofMinutes(5), "", roles)).serialize();
    }

    private static String issue(String subject, Duration lifetime, String scope, String... roles) {
        return sign(claims(subject, lifetime, scope, roles), TRUSTED_KEYS);
    }

    private static JWTClaimsSet claims(String subject, Duration lifetime, String scope, String... roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
            .subject(subject)
            .issueTime(Date.from(now.minusSeconds(600)))
            .expirationTime(Date.from(now.plus(lifetime)))
            .claim("roles", List.of(roles))
            .claim("scope", scope)
            .build();
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys) {
        try {
            SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            token.sign(new RSASSASigner(keys.getPrivate()));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
