package uk.gov.moj.cpp.prisoncourtregister.integration;

import uk.gov.moj.cpp.prisoncourtregister.integration.config.FunctionsHostClient;
import uk.gov.moj.cpp.prisoncourtregister.integration.config.PcrServiceStub;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for tests that run against the docker-compose stack (`functions` + `wiremock`).
 *
 * <p>Placement mirrors {@code IntegrationTestBase} in {@code service-cp-crime-results-pcr}. Execution
 * differs deliberately: these run under their own {@code integrationTest} task, which
 * {@code dockerCompose.isRequiredBy} wires to bring the stack up beforehand and down afterwards. So
 * there is no "is the stack running?" branch here — if you got this far, {@code composeUp} succeeded.
 *
 * <pre>
 * ./gradlew integrationTest   # stack up, run, stack down
 * ./gradlew test              # unit tests only, no Docker
 * ./gradlew build             # both
 * </pre>
 */
public abstract class IntegrationTestBase {

    @BeforeAll
    static void awaitStack() {
        FunctionsHostClient.awaitReady();
    }

    @BeforeEach
    void resetStub() {
        PcrServiceStub.reset();
    }
}
