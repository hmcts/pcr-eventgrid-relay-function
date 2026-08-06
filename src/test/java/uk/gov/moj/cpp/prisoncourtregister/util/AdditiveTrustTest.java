package uk.gov.moj.cpp.prisoncourtregister.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real CA bundle is never committed — it holds internal certificates and this repo is public — so
 * these tests generate a throwaway self-signed CA at runtime rather than using a fixture.
 */
class AdditiveTrustTest {

    @TempDir
    static Path tempDir;

    private static Path caPem;
    private static int platformAnchors;

    @BeforeAll
    static void generateThrowawayCa() throws Exception {
        caPem = tempDir.resolve("throwaway-ca.pem");
        final Process openssl = new ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", tempDir.resolve("throwaway-ca.key").toString(),
                "-out", caPem.toString(),
                "-days", "1", "-subj", "/CN=throwaway-test-ca")
                .redirectErrorStream(true)
                .start();
        assertThat(openssl.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(openssl.exitValue()).as("openssl should generate a test CA").isZero();

        platformAnchors = AdditiveTrust.platformTrustAnchorCount();
        assertThat(platformAnchors)
                .as("the JVM must ship default trust anchors, or the additive assertion proves nothing")
                .isPositive();
    }

    @Test
    @DisplayName("no bundle configured leaves the default SSL context in place")
    void emptyWhenUnconfigured() {
        assertThat(AdditiveTrust.fromPemBundle(Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("a configured but missing bundle degrades to the default context rather than throwing")
    void emptyWhenFileMissing() {
        final Path absent = tempDir.resolve("does-not-exist.pem");

        assertThat(AdditiveTrust.fromPemBundle(Optional.of(absent.toString()))).isEmpty();
    }

    @Test
    @DisplayName("a valid bundle produces an SSL context")
    void buildsContextFromBundle() {
        assertThat(AdditiveTrust.fromPemBundle(Optional.of(caPem.toString()))).isPresent();
    }

    @Test
    @DisplayName("trust is additive: the bundle's CA is added to the platform defaults, not substituted")
    void trustIsAdditive() throws Exception {
        final KeyStore store = AdditiveTrust.trustStoreWith(caPem);

        // The whole point. Trusting only the private CA would silently break TLS to every public
        // endpoint — a failure that surfaces later and somewhere unrelated.
        assertThat(store.size())
                .as("platform anchors (%d) plus the one bundled CA", platformAnchors)
                .isEqualTo(platformAnchors + 1);
    }

    @Test
    @DisplayName("a bundle with no certificates fails loudly rather than trusting nothing extra")
    void throwsOnBundleWithoutCertificates() throws IOException {
        final Path junk = Files.writeString(tempDir.resolve("junk.pem"), "not a certificate\n");

        assertThatThrownBy(() -> AdditiveTrust.fromPemBundle(Optional.of(junk.toString())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not build an SSL context");
    }
}
