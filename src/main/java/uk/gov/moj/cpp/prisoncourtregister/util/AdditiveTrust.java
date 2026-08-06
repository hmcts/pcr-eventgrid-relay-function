package uk.gov.moj.cpp.prisoncourtregister.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds an {@link SSLContext} that trusts the JVM's default CAs <strong>plus</strong> extra CAs from a
 * PEM bundle.
 *
 * <p>Why this exists: the PCR service's internal ingress presents a certificate from a private CA that
 * no JVM ships, so TLS to it fails with a handshake error. The Node sibling apps solve the same problem
 * with {@code NODE_EXTRA_CA_CERTS}; the JVM has no equivalent — it will not read a PEM from an
 * environment variable — so the trust store has to be assembled in code.
 *
 * <p><strong>Additive, never replacing.</strong> The default trust anchors are copied in alongside the
 * bundle. Trusting only the private CA would silently break TLS to every public endpoint, which is the
 * kind of failure that surfaces much later and somewhere unrelated.
 *
 * <p>No CA certificates are committed to this repository — it is public, and they carry internal domain
 * names. The bundle is provisioned onto the Function App and located via
 * {@code PCR_SERVICE_CA_BUNDLE_PATH}.
 */
public final class AdditiveTrust {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdditiveTrust.class);

    private AdditiveTrust() {
    }

    /**
     * An SSL context trusting the JVM defaults plus every certificate in {@code pemBundle}, or empty
     * when no usable bundle is configured — in which case callers should leave the default SSL context
     * in place.
     *
     * @throws IllegalStateException if the bundle exists but cannot be read or parsed. A configured but
     *                               broken bundle is a deployment error worth failing loudly on, not
     *                               something to silently fall back from — the fallback would be a
     *                               confusing TLS failure at the first relay instead.
     */
    public static Optional<SSLContext> fromPemBundle(final Optional<String> pemBundle) {
        return pemBundle
                .map(Path::of)
                .filter(AdditiveTrust::isReadableOrWarn)
                .map(AdditiveTrust::buildOrThrow);
    }

    private static boolean isReadableOrWarn(final Path path) {
        final boolean readable = Files.isReadable(path);
        if (!readable) {
            LOGGER.warn("CA bundle {} is not readable - using the default SSL context. TLS to a host "
                    + "with a private CA will fail.", path);
        }
        return readable;
    }

    private static SSLContext buildOrThrow(final Path path) {
        try {
            return build(path);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not build an SSL context from CA bundle " + path, e);
        }
    }

    private static SSLContext build(final Path pemBundle) throws IOException, GeneralSecurityException {
        final KeyStore trustStore = trustStoreWith(pemBundle);

        final TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);

        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    /**
     * The assembled trust store: platform defaults plus the bundle.
     *
     * <p>Package-private so tests can assert on its contents. {@link SSLContext} exposes no way to read
     * back the trust managers it was initialised with, so the store is the only honest thing to
     * assert against without doing a full TLS handshake.
     */
    /* default */ static KeyStore trustStoreWith(final Path pemBundle)
            throws IOException, GeneralSecurityException {
        final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        final int defaults = addDefaultTrustAnchors(trustStore);
        final int extras = addBundle(trustStore, pemBundle);

        LOGGER.info("Trusting {} default CA(s) plus {} from {}", defaults, extras, pemBundle);
        return trustStore;
    }

    /** Number of trust anchors the JVM ships with, for comparison against an assembled store. */
    /* default */ static int platformTrustAnchorCount() throws GeneralSecurityException {
        final TrustManagerFactory platform =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        platform.init((KeyStore) null);

        int total = 0;
        for (final TrustManager manager : platform.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                total += x509.getAcceptedIssuers().length;
            }
        }
        return total;
    }

    /** Copies the platform's own trust anchors in, so public TLS keeps working. */
    private static int addDefaultTrustAnchors(final KeyStore trustStore) throws GeneralSecurityException {
        final TrustManagerFactory platform =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        platform.init((KeyStore) null);

        int added = 0;
        for (final TrustManager manager : platform.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (final X509Certificate issuer : x509.getAcceptedIssuers()) {
                    trustStore.setCertificateEntry("default-" + added, issuer);
                    added++;
                }
            }
        }
        return added;
    }

    private static int addBundle(final KeyStore trustStore, final Path pemBundle)
            throws IOException, GeneralSecurityException {
        final CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        final Collection<? extends Certificate> parsed;
        try (InputStream in = Files.newInputStream(pemBundle)) {
            parsed = certificates.generateCertificates(in);
        }
        if (parsed.isEmpty()) {
            throw new GeneralSecurityException("No X.509 certificates found in " + pemBundle);
        }

        int added = 0;
        for (final Certificate certificate : parsed) {
            trustStore.setCertificateEntry("bundle-" + added, certificate);
            added++;
        }
        return added;
    }
}
