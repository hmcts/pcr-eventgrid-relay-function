package uk.gov.moj.cpp.prisoncourtregister.integration.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.util.List;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * The WireMock service started by docker-compose, standing in for
 * {@code service-cp-crime-results-pcr}.
 *
 * <p>Driven over WireMock's admin API rather than in-process, because the container under test has to
 * reach it over the compose network — the same reason {@code PostgresInitialise} points at a
 * compose-started database in {@code service-cp-crime-results-pcr}.
 */
public final class PcrServiceStub {

    /** The operation this app relays to: {@code POST /internal/hearing-results}. */
    public static final String PCR_PATH = "/internal/hearing-results";

    /** Host port published by the `wiremock` service in docker-compose.yml. */
    private static final String HOST = "localhost";
    private static final int PORT = 8089;

    private static final WireMock CLIENT = WireMock.create().host(HOST).port(PORT).build();

    private PcrServiceStub() {
    }

    public static void reset() {
        CLIENT.resetMappings();
        CLIENT.resetRequests();
    }

    /** Makes the stub answer every relay with {@code status}. */
    public static void respondWith(final int status) {
        CLIENT.register(post(urlEqualTo(PCR_PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    }

    public static List<LoggedRequest> relayedRequests() {
        return CLIENT.find(postRequestedFor(urlEqualTo(PCR_PATH)));
    }

    public static int relayCount() {
        return relayedRequests().size();
    }
}
