# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A single-module Gradle project producing **one thin Java Azure Function app**. It listens for
`Hearing_Resulted` Event Grid events and POSTs each, verbatim, to the PCR service's
`POST /internal/hearing-results` endpoint.

```
Event Grid (Hearing_Resulted) ──▶ PrisonCourtRegisterHearingResulted ──POST──▶ service-cp-crime-results-pcr
```

That is the whole responsibility. **Resist adding orchestration, Redis lookups, completeness retries,
register building, or persistence here** — all of it belongs to `service-cp-crime-results-pcr`
(`ResultsIngestionService.ingestAndPersist`). If a change requires understanding the PCR payload
beyond `data.hearingId`, it probably belongs in that service instead.

### Why it exists

The accepted PCR ingestion design (ADR-007 / AMP-892, in
`../service-cp-crime-results-pcr/docs/designs/2026-07-29-pcr-eventgrid-webhook-ingestion-design.md`)
had Event Grid delivering directly to a webhook on the PCR service, which forced that service to own
a public HTTPS endpoint, the `Microsoft.EventGrid.SubscriptionValidationEvent` handshake, and network
isolation in place of app auth. This Function App absorbs that surface: the `@EventGridTrigger`
binding answers the validation handshake itself, so no handshake code exists in either codebase.

### What it does NOT do — correct this if you see it stated otherwise

**It does not replace the legacy JS durable-functions chain**, and it is not a rewrite of it. The
`prisoncourtregister-azure-functions` packaging module in `cpp-context-azure-legalaidagency`
(trigger → orchestrator → cache query → SetPrisonCourtRegister → subscriptions → outbound →
process-outbound) keeps running, on its own Event Grid subscription, producing the PDF/email PCR
distribution. The PCR API-marketplace design is explicit — §13 non-goals: *"Changing or retiring
existing email/post PCR distribution — additional channel, not a replacement"* — and §4a notes the
legacy app already listens to the same `Hearing_Resulted` event.

Two pipelines, one topic, two subscriptions. This relay feeds the new pull-based read channel
*alongside* the existing PDF path. See the diagram in README.md.

Consequences to respect:

- **The legacy app is a live dependency.** Design §4d warns that the Event Grid subscription *and*
  the Redis cache the PCR service reads may be provisioned as part of the legacy Function App's own
  Azure resources — retiring it could remove the PCR service's trigger and its primary data lookup at
  once. Never write or reason as if it is decommissioned.
- **Register-building logic is reimplemented, not migrated.** The PCR service ports the decision and
  transform logic (design §5); the legacy app keeps its own copy for the PDF path. The two need
  keeping in step.

## Build & Test Commands

**Gradle**, deliberately mirroring `service-cp-crime-results-pcr`: same wrapper (9.6.1), same Java 25
toolchain, same `gradle/*.gradle` convention-script split, same `.github/pmd-ruleset.xml`.

```bash
./gradlew test                       # unit tests only — no Docker
./gradlew integrationTest            # integration tests — starts/stops the compose stack itself
./gradlew build                      # both suites + JaCoCo (needs Docker)
./gradlew pmdMain                    # PMD — explicit-only, as in the PCR service
./gradlew azureFunctionsPackage      # staging dir: build/azure-functions/<appName>/
./gradlew azureFunctionsPackageZip   # deployable zip: build/azure-functions/<appName>.zip
./gradlew azureFunctionsRun          # run locally (needs local.settings.json in the repo root)
./gradlew azureFunctionsDeploy       # needs `az login` (auth type azure_cli)
```

Requires a **JDK 25** — the Gradle toolchain pins `JavaLanguageVersion.of(25)`. Compilation also runs
with `-Xlint:unchecked -Werror` (from `gradle/java.gradle`), so warnings fail the build.

`version` comes from `-DARTEFACT_VERSION=...`, defaulting to `0.0.999`, as in the PCR service.

## Local setup

Copy `Azure/local.settings.sample.json` to `local.settings.json` in the **repo root** (git-ignored —
never commit it). Only `PCR_SERVICE_INGESTION_ENDPOINT` is required; everything else has a default.
See the config table in README.md.

## Layout

```
build.gradle                      plugins, dependencies, azurefunctions block, prune task
settings.gradle                   rootProject.name
gradle/
├── java.gradle                   toolchain 25, -Werror, idea
├── repositories.gradle           mavenCentral + HMCTS Azure Artifacts (no publishing)
├── test.gradle                   JUnit platform, jacoco xml, check -> jacocoTestReport
├── pmd.gradle                    PMD, explicit-only, main only
└── docker-test.gradle            dockerCompose config + prepareFunctionsHostSecrets
docker-compose.yml                functions (app under test) + wiremock (PCR stub)
.github/pmd-ruleset.xml           copied from service-cp-crime-results-pcr

src/main/java/uk/gov/moj/cpp/prisoncourtregister/
├── PrisonCourtRegisterHearingResultedFunction.java   @FunctionName + @EventGridTrigger
├── SystemVariables.java                              env-var name constants
├── model/     ForwardableEvent (hearingId + raw JsonNode envelope)
├── service/   EventForwarder (interface), HttpEventForwarder, ForwardingException,
│              EventParsingException
└── util/      EnvVarUtil, ObjectMapperFactory
```

## Two Gradle traps, already sprung — do not "fix" these back

1. **`azure-functions-java-library` must be `implementation`, never `compileOnly`.**
   `azureFunctionsPackage` scans the *runtime* classpath for `@FunctionName`; with `compileOnly` it
   dies on `ClassNotFoundException: com.microsoft.azure.functions.annotation.FunctionName`. Maven's
   `provided` scope did not have this problem, so this differs from the pattern in
   `cp-ai-rag-service`.
2. **`pruneWorkerProvidedJars` is load-bearing.** Because of (1) the plugin stages that library into
   `lib/`, and it does **not** exclude it itself (verified, despite what its docs imply). The Java
   worker supplies its own copy; two copies risk binding-resolution errors. The task deletes it after
   packaging so `lib/` holds the same 7 jars the Maven build produced. Add new worker-provided
   artifacts to `workerProvidedJarPatterns`.

Also note `.gitignore` has `*.jar` followed by `!gradle/wrapper/gradle-wrapper.jar`. The negation must
stay after the exclusion or `./gradlew` cannot bootstrap on a fresh clone.

## CI — GitHub Actions

Mirrors `service-cp-crime-results-pcr`. There is **no `azure-pipelines.yaml`**; it was removed
because the shared `context-verify` template drives Maven and the CPP pool only offers Java 21
agents, so it could never have built this repo. Java 25 comes from `actions/setup-java@v5`.

`ci-draft.yml` (PR + push to main) and `ci-released.yml` (release) both call the reusable
`ci-build-deploy.yml`: artefact version → Gradle build + tests → `azureFunctionsPackageZip` → verify
zip → deploy. Plus `code-analysis.yml` (PMD), `codeql.yml` (CodeQL + CycloneDX SBOM),
`secrets-scanner.yml`, `auto-merge-dependabot.yml`.

Carried over from PCR but deliberately dropped: the publish/Docker/ACR/AKS jobs (this ships a
Functions zip, not a container), `composeUp`/`composeDown` (no container-backed tests), and the DAST
job (this app exposes no HTTP surface for ZAP to scan).

**The `Verify packaged zip` step is load-bearest — do not delete it to speed CI up.** It catches three
things that otherwise only appear at runtime, in Azure, as obscure errors: a missing
`eventGridTrigger` binding, a `function.json` `scriptFile` that does not match the versioned jar
actually in the zip (a real risk since CI injects `ARTEFACT_VERSION`), and `azure-functions-java-library`
having been shipped in `lib/` because `pruneWorkerProvidedJars` silently stopped working.

## Conventions

- **Relay, don't reshape.** The envelope travels as a `JsonNode`, not a typed record, and is POSTed
  inside a single-element array. Modelling it as a record would silently drop fields the app doesn't
  read (`topic`, `dataVersion`, `metadataVersion`, anything added later). Do not "tidy" this into a
  DTO — losing passthrough fidelity is the bug it prevents.
- **Functions are thin.** The `@FunctionName` class parses, guards, delegates. Logic lives in
  `service/`.
- **Constructor injection with a no-arg production constructor.** Each class has a public no-arg
  constructor that reads env vars and builds real collaborators, plus a package-private constructor
  taking collaborators for tests. Mirrors `ai-document-answer-scoring-function` in `cp-ai-rag-service`.
- **Log via SLF4J** (`LoggerFactory.getLogger`), backed by log4j2 (`src/main/resources/log4j2.xml`).
  Not `context.getLogger()`. Prefix messages with `[Hearing ID: {}]` — it is the correlation key
  across the estate and how the legacy JS functions logged.
- **Env vars**: add the name to `SystemVariables`, read through `EnvVarUtil`. Required settings use
  `getRequiredEnv` so a misconfigured app fails at construction, not per-event. Document every new
  setting in `Azure/local.settings.sample.json` **and** the README config table.
- **No mocking framework.** JUnit 5 + AssertJ. Use hand-written doubles (`RecordingEventForwarder`,
  `TestExecutionContext`) and, for HTTP, a real JDK `com.sun.net.httpserver.HttpServer` — see
  `HttpEventForwarderTest`. Keep it that way; it is one fewer Java-version-compatibility risk on a
  bleeding-edge JDK. (WireMock appears only in the `integration` package, as a *client* against the
  compose-started stub — not as an in-process server standing in for a collaborator.)

## Integration tests

**Placement mirrors `service-cp-crime-results-pcr`; execution is deliberately split. Keep both.**

Placement (same as PCR — do not move these into a separate source set):

- Tests live in the **normal `src/test` source set**, package
  `uk.gov.moj.cpp.prisoncourtregister.integration`, named `*IntegrationTest` — not a separate
  `integrationTest` source set, and not `*IT`.
- Infrastructure helpers go in `integration/config` (`FunctionsHostClient`, `PcrServiceStub`), the
  counterparts of PCR's `PostgresInitialise` / `RedisInitialise`.
- `IntegrationTestBase` waits for readiness and resets stub state, like PCR's `IntegrationTestBase`.
- Containers are started **externally by docker-compose** (`com.avast.gradle.docker-compose`,
  configured in `gradle/docker-test.gradle`), **not** by the test JVM. No Testcontainers.

**Execution is split** — this is a deliberate difference from PCR, which runs everything in `test`:

| Task | Runs | Docker |
|---|---|---|
| `test` | unit only — `exclude '**/integration/**'` | no |
| `integrationTest` | `include '**/integration/**'`, same source set | yes, self-managed |
| `build` | both, via `check` | yes |

`dockerCompose.isRequiredBy(integrationTest)` brings the stack up before and down after, **including
on failure** (verified). So there is no `composeUp`/`composeDown` sequencing in CI — a single
`./gradlew build` — and no "is the stack running?" branch in the test code. `composeUp` depends on
`azureFunctionsPackage` and `prepareFunctionsHostSecrets`.

Shared Test config lives in the `tasks.withType(Test)` block in `gradle/test.gradle`; put anything
common there rather than on an individual task. `jacocoTestReport` aggregates every `.exec` under
`build/jacoco`, so coverage is the union of both suites, not whichever ran last.

`docker-compose.yml`: `functions` (the packaged app in `azure-functions/java:4-java25`, port 7071)
and `wiremock` (stands in for the PCR service, port 8089). The app reaches the stub over the compose
network; tests drive both over published host ports.

It all works with no Azure resources because the Event Grid trigger *is* an HTTP webhook:
`POST /runtime/webhooks/eventgrid?functionName=<name>&code=<system key>` with `aeg-event-type` of
`Notification` or `SubscriptionValidation`.

**The handshake test is the most important test in the repo.** It is the only evidence for the claim
that justifies this app existing — that the binding answers the subscription-validation handshake so
neither codebase needs handshake code. Do not delete it.

Empirically confirmed status codes (do not "correct" these from intuition): success **202**,
deliberate skip **202**, failed invocation **500**, handshake **200** with a `validationResponse` body.

Three traps already sprung, each of which cost real debugging:

1. **The webhook needs the `eventgrid_extension` system key** — 401 without it, and
   `AZURE_FUNCTIONS_ENVIRONMENT=Development` does *not* waive it. `prepareFunctionsHostSecrets`
   generates a seeded secret store into `build/` (never committed — the secrets scanner would flag
   key-shaped content), and `composeUp` depends on it.
2. **`composeUp`'s TCP wait is not readiness.** The .NET host binds port 80 well before the Java
   worker has loaded the function, so tests must go through `FunctionsHostClient.awaitReady()`.
3. **PMD is disabled for every source set except main, generically.** The PMD plugin creates a task
   per source set, so naming `pmdTest` individually would mean the next source set silently wires a
   new PMD task into `check` and breaks `build`. See `gradle/pmd.gradle`.
- `azure-functions-java-library` is **`provided`** scope. The Java worker supplies it at runtime; a
  second copy in `lib/` risks binding-resolution errors.

## Error-handling contract

These mirror the PCR endpoint's documented semantics. Do not change them without a deliberate
decision — each is load-bearing:

- `200` → done.
- `503` → the service's "hearing details not complete yet" signal. **Retryable**; after
  `FORWARD_MAX_ATTEMPTS` it propagates so Event Grid redelivers on its own backoff.
- `400` → malformed / unrecognised `eventType`. The contract calls this permanent, so fail
  immediately; retrying only wastes the budget.
- Other 5xx / `429` / `408` / I/O → retry, then propagate.
- Missing or blank `data.hearingId` → log WARN, **return normally**. Matches the legacy trigger, which
  started no orchestration for such events. Also covers a validation event reaching the handler. Not
  an error.
- Malformed JSON → throw `EventParsingException`. It will never succeed on retry, but throwing makes
  the loss visible via dead-lettering instead of silent.

In-process retries sit *under* Event Grid's retry policy, so total attempts multiply. Keep
`FORWARD_MAX_ATTEMPTS × FORWARD_RETRY_DELAY_IN_SECONDS` well below the Function App timeout.

## Outstanding decisions

Check whether these are still open before touching the related code:

1. **`eventType` filtering.** The PCR spec allows only `Hearing_Resulted` and answers 400
   (non-retryable) otherwise, but the topic also carries `Hearing_Resulted_Complex` (see
   `Constants.HEARING_RESULTED_COMPLEX` and `HearingResultedDVLAEventGridTrigger/index.js:17` in
   `cpp-context-azure-legalaidagency`). This app deliberately does not filter. The fix belongs in the
   `pcr-hearing-results` subscription filter, not here — do not add client-side `eventType` filtering
   without first confirming that decision.
2. **CI deploy credentials are not provisioned.** CI is GitHub Actions (see below) and builds green,
   but the `Deploy` job in `ci-build-deploy.yml` needs an Entra federated credential for this repo
   plus `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_SUBSCRIPTION_ID`. Without them it **skips with
   a warning** — deliberately, so PRs stay green and nothing pretends to have deployed. Do not
   "fix" that skip into a hard failure until the secrets exist.
3. **Azure coordinates unconfirmed.** `appName`, `resourceGroup` in `build.gradle`'s `azurefunctions`
   block need platform-team sign-off. `resourceGroup` is still `RG-STE-CCP0121-HEARINGRES`, inherited from the legacy
   LAA app and likely wrong for an AMP-owned service. If deployment should stay in ADO rather than
   Actions, replace the `Deploy` job with `hmcts/trigger-ado-pipeline`.
4. **Repointing the subscription.** `pcr-hearing-results` / `eg-ste-ccp0121-hearingres` currently
   targets the PCR service webhook per ADR-007. Moving it to this Function App is a platform-team
   change and the PCR service's webhook controller becomes redundant once done.

## Branch & release strategy

JGitFlow, matching the estate: `main` = develop, `dev/release` = master, features `dev/feature-*`,
releases `dev/release-*`, hotfixes `dev/hotfix-*`.
