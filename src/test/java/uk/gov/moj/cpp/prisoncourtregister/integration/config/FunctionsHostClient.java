package uk.gov.moj.cpp.prisoncourtregister.integration.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.awaitility.Awaitility;

/**
 * Talks to the Azure Functions host started by docker-compose, the way Event Grid does.
 *
 * <p>Sibling of {@code PostgresInitialise} / {@code RedisInitialise} in
 * {@code service-cp-crime-results-pcr}: the container itself is started externally by
 * {@code composeUp}, and this class only knows how to reach it and wait for it to be ready.
 *
 * <p>The Event Grid trigger is served by the host as an ordinary HTTP webhook, which is what makes
 * this testable with no Azure resources at all:
 * {@code POST /runtime/webhooks/eventgrid?functionName=...&code=...} with an {@code aeg-event-type}
 * header of {@code Notification} or {@code SubscriptionValidation}.
 */
public final class FunctionsHostClient {

    /** Host port published by the `functions` service in docker-compose.yml. */
    private static final String BASE_URL = "http://localhost:7071";

    public static final String FUNCTION_NAME = "PrisonCourtRegisterHearingResulted";

    /**
     * Must match the system key seeded by the {@code prepareFunctionsHostSecrets} Gradle task. The
     * host answers 401 without it; {@code AZURE_FUNCTIONS_ENVIRONMENT=Development} does not waive it.
     */
    private static final String SYSTEM_KEY = "integrationTestSystemKey";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private FunctionsHostClient() {
    }

    /**
     * Blocks until the host has loaded the app and is answering the Event Grid webhook.
     *
     * <p>{@code composeUp}'s TCP wait only proves the port is open — the .NET host binds it well
     * before the Java worker has loaded the function. Sending an empty batch is a harmless probe: it
     * invokes nothing, so a sub-400 answer means the function is registered and callable.
     *
     * <p>Assumes the stack is already up — the {@code integrationTest} task guarantees that via
     * {@code dockerCompose.isRequiredBy}, so there is no liveness branch here.
     */
    public static void awaitReady() {
        Awaitility.await("Azure Functions host ready")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .until(() -> deliverNotification("[]").statusCode() < 400);
    }

    /** Delivers an Event Grid notification: a JSON array, {@code aeg-event-type: Notification}. */
    public static HttpResponse<String> deliverNotification(final String eventArrayJson) {
        return post(eventArrayJson, "Notification");
    }

    /** Sends the subscription-validation handshake Event Grid performs on first subscribe. */
    public static HttpResponse<String> deliverSubscriptionValidation(final String eventArrayJson) {
        return post(eventArrayJson, "SubscriptionValidation");
    }

    private static HttpResponse<String> post(final String body, final String aegEventType) {
        final URI uri = URI.create("%s/runtime/webhooks/eventgrid?functionName=%s&code=%s"
                .formatted(BASE_URL, FUNCTION_NAME, SYSTEM_KEY));

        final HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("aeg-event-type", aegEventType)
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
                .build();

        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    ("Could not reach the Functions host at %s. Run via ./gradlew integrationTest, "
                            + "which starts the compose stack; or ./gradlew composeUp to start it by hand.")
                            .formatted(uri), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling the Functions host", e);
        }
    }
}
