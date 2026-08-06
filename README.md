# PCR Event Grid Relay Function

A **thin** Java Azure Function app. It listens for `Hearing_Resulted` Event Grid events and relays
each one, untouched, to the PCR service's ingestion endpoint. Nothing else.

```
Azure Event Grid                    this Function App                 service-cp-crime-results-pcr
topic: Hearing_Resulted  ──────▶  PrisonCourtRegisterHearingResulted  ──POST──▶  /internal/hearing-results
   (pointer event)                @EventGridTrigger                              (EventGridSchema array)
```

## Why this app exists

The accepted PCR ingestion design ([ADR-007 / AMP-892](../service-cp-crime-results-pcr/docs/designs/2026-07-29-pcr-eventgrid-webhook-ingestion-design.md))
has Event Grid delivering **straight to a webhook** on `service-cp-crime-results-pcr`. That pulls an
Event Grid-shaped surface into the service:

- a public-ish HTTPS endpoint it owns and secures,
- the `Microsoft.EventGrid.SubscriptionValidationEvent` handshake, implemented by hand,
- network isolation standing in for application-level auth (`security: []`).

Putting this Function App in front moves that surface out of the service. The
`@EventGridTrigger` binding performs the subscription-validation handshake itself, so **no handshake
code is needed anywhere**, and the PCR service is left receiving an ordinary internal HTTP call.

> That handshake claim is **verified**, not assumed: `EventGridRelayIntegrationTest` sends a real
> `Microsoft.EventGrid.SubscriptionValidationEvent` to the real Functions host and asserts it answers
> `200 {"validationResponse": "<code>"}` without the event ever reaching the PCR service. If that
> test is ever deleted, this design's main justification becomes unevidenced again.

No durable task hub, no Redis, no orchestration state — this app holds no state at all.

## What this does NOT replace

**The legacy JavaScript durable-functions chain keeps running.** The
`prisoncourtregister-azure-functions` packaging module in
[`cpp-context-azure-legalaidagency`](../../cpp/cpp-context-azure-legalaidagency) is *not* being
retired by this work, and this app is *not* a rewrite of it. The PCR API-marketplace design says so
explicitly — §13 non-goals: *"Changing or retiring existing email/post PCR distribution — additional
channel, not a replacement"* — and §4a notes the legacy Function App **already listens to the same
`Hearing_Resulted` event** on its own subscription.

The two pipelines run side by side, each with its own Event Grid subscription off the same topic:

```
                          Azure Event Grid — topic: Hearing_Resulted
                                        │
              ┌─────────────────────────┴──────────────────────────┐
              │ (existing subscription)          (pcr-hearing-results)
              ▼                                                    ▼
  PrisonCourtRegisterEventGridTrigger                 PrisonCourtRegisterHearingResulted
    └─ PrisonCourtRegisterOrchestrator                   (this app)
         ├─ HearingResultedCacheQuery (Redis)                       │
         ├─ SetPrisonCourtRegister                                  ▼
         ├─ PrisonCourtRegisterSubscriptions        POST /internal/hearing-results
         ├─ OutboundPrisonCourtRegister                             │
         └─ ProcessOutboundPrisonCourtRegister                      ▼
              │                                        service-cp-crime-results-pcr
              ▼                                        (Redis lookup, completeness
   Progression → Docmosis → PDF/email                   retries, versioned persistence)
   (unchanged, still the system of record                       │
    for PCR distribution)                                       ▼
                                                    pull-based read API for
                                                    API Marketplace subscribers
```

So this relay adds a channel; it removes nothing. Two consequences worth holding onto:

- **The legacy chain is a live dependency, not dead weight.** The PCR design (§4d) warns that both the
  Event Grid subscription *and* the Redis cache the PCR service reads may be provisioned as part of
  the legacy Function App's own Azure resources — "if the Function App is retired, this service's
  trigger *and* its primary data lookup could both disappear at once". Do not treat the legacy app as
  safe to switch off.
- **Register-building logic is reimplemented, not moved.** The PCR service ports the *decision and
  transform* logic (design §5, "what to port, what not to"); the legacy app keeps its own copy for the
  PDF path. Expect the two to need keeping in step.

## The function

| `@FunctionName` | Trigger | Behaviour |
|---|---|---|
| `PrisonCourtRegisterHearingResulted` | Event Grid | Read `data.hearingId` for correlation → relay the envelope verbatim |

### What gets sent

The event is relayed **verbatim**, wrapped in the single-element JSON array that
`POST /internal/hearing-results` expects (`requestBody` is an array of EventGridSchema events).
Relaying rather than reshaping means the PCR service needs **no new contract** for this app — it
receives exactly the request Event Grid itself would have made.

```json
[
  {
    "id": "evt-1",
    "topic": "/subscriptions/…/topics/hearing",
    "subject": "hearing/resulted",
    "eventType": "Hearing_Resulted",
    "eventTime": "2026-08-04T09:15:00Z",
    "dataVersion": "1.0",
    "data": {
      "hearingId": "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
      "hearingDay": "2026-08-04",
      "userId": "7aee5dea-b0de-4604-b49b-86c7788cfc4b"
    }
  }
]
```

The app models only `data.hearingId` (to skip pointless deliveries and correlate logs). Everything
else — including fields the publisher adds later — passes through untouched.

### Behaviour on failure

Status handling follows the endpoint's documented semantics:

| Situation | What happens |
|---|---|
| `200` | Accepted; done |
| `503` | The service's "hearing details not complete yet" signal → **retried**, then propagated so Event Grid redelivers |
| `400` | Malformed / unrecognised `eventType`. The contract calls this permanent, so it fails immediately without burning the retry budget |
| Other 5xx, `429`, `408`, I/O error | Retried up to `FORWARD_MAX_ATTEMPTS`, then propagated |
| No `hearingId` on the event | Logged at WARN and **skipped** — matches the legacy trigger, which started no orchestration. Also covers a validation event, should one ever reach the handler |
| Malformed JSON | `EventParsingException` propagates → Event Grid retries, then dead-letters. Deliberately not swallowed, so the loss is visible |

In-process retries sit **under** Event Grid's own retry policy (exponential backoff, up to 24 hours),
so total attempts multiply. Keep `FORWARD_MAX_ATTEMPTS × FORWARD_RETRY_DELAY_IN_SECONDS` comfortably
below the Function App timeout.

## `eventType` filtering — resolved

The PCR service answers **400 (non-retryable)** for an `eventType` it does not recognise — its
`HearingResultedWebhookService` branches on the value. Since this app relays whatever the subscription
delivers and does **not** filter on `eventType`, that looked like a live risk: the topic also carries
`Hearing_Resulted_Complex` (`Constants.HEARING_RESULTED_COMPLEX` in the legacy code).

(The rejection is a service-side branch, not schema validation — in the shipped spec
`HearingResultedWebhookEvent.eventType` is a plain string with `Hearing_Resulted` only as an *example*,
not an enum. An earlier draft of this README claimed an enum, from the design doc rather than the
built artefact.)

**Checked against the real topic (`eg-ste-ccp0121-hearingres`) — the risk does not apply.** Event types
are already separated across subscriptions:

```
egs-prison-court       AzureFunction   includedEventTypes = [Hearing_Resulted]
egs-court-register     AzureFunction   includedEventTypes = [Hearing_Resulted]
egs-laa                AzureFunction   includedEventTypes = [Hearing_Resulted]
egs-nowsce-complex     StorageQueue    includedEventTypes = [Hearing_Resulted_Complex]   ← separate
egs-sjp-hearing-resulted  AzureFunction  includedEventTypes = [SJP_Hearing_Resulted]
```

`Hearing_Resulted_Complex` goes to a different subscription entirely. So this app's subscription simply
needs `--included-event-types Hearing_Resulted`, exactly like its seven siblings, and no client-side
filtering is warranted. See the deployment section.

## Configuration

Copy `Azure/local.settings.sample.json` to `local.settings.json` in the repo root (git-ignored — it
holds keys, never commit it).

| Setting | Required | Default | Purpose |
|---|---|---|---|
| `PCR_SERVICE_INGESTION_ENDPOINT` | yes | — | Absolute URL of `POST /internal/hearing-results`. Missing/blank fails fast at startup |
| `PCR_SERVICE_INGRESS_HEADER_NAME` | no | — | Header an ingress fronting the service requires. Applied only when both name and value are set |
| `PCR_SERVICE_INGRESS_HEADER_VALUE` | no | — | Value for the above |
| `FORWARD_MAX_ATTEMPTS` | no | `3` | Total attempts per delivery, including the first |
| `FORWARD_RETRY_DELAY_IN_SECONDS` | no | `2` | Fixed delay between attempts |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | no | `10` | TCP connect timeout |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | no | `30` | Per-request response timeout |

The endpoint is `security: []` per ADR-007 (network-isolated, no bearer token or subscription key),
so the ingress-header settings are normally left unset.

The Event Grid subscription that delivers to this function is infrastructure, configured outside this
repo. Per the PCR design, the existing subscription is `pcr-hearing-results` / endpoint
`eg-ste-ccp0121-hearingres`; repointing it from the PCR service's webhook to this Function App is a
platform-team change.

## Build & test

Gradle, matching [`service-cp-crime-results-pcr`](../service-cp-crime-results-pcr) — same wrapper
version (9.6.1), same Java 25 toolchain, same `gradle/*.gradle` convention-script layout, same PMD
ruleset (`.github/pmd-ruleset.xml`).

```bash
./gradlew test                       # unit tests only — no Docker
./gradlew integrationTest            # integration tests — starts and stops the stack itself
./gradlew build                      # both suites + JaCoCo (needs Docker)
./gradlew pmdMain                    # PMD (explicit-only, as in the PCR service)
./gradlew azureFunctionsPackage      # staging dir: build/azure-functions/<appName>/
./gradlew azureFunctionsPackageZip   # deployable zip: build/azure-functions/<appName>.zip
./gradlew azureFunctionsRun          # run locally (needs local.settings.json)

./gradlew test --tests HttpEventForwarderTest
./gradlew test --tests '*PrisonCourtRegisterHearingResultedFunctionTest.skipsEventWithoutHearingId'
```

Unit tests are JUnit + AssertJ only — no mocking framework. `HttpEventForwarderTest` drives the real
`java.net.http.HttpClient` against a JDK `com.sun.net.httpserver.HttpServer`, so status handling,
retry counts and the exact relayed body are verified against actual HTTP rather than stubs.

### Integration tests

**Placement** follows [`service-cp-crime-results-pcr`](../service-cp-crime-results-pcr): the tests live
in the **normal `src/test` source set**, package
`uk.gov.moj.cpp.prisoncourtregister.integration`, named `*IntegrationTest`, with infrastructure
helpers under `integration/config` (the counterparts of PCR's `PostgresInitialise` /
`RedisInitialise`) and an `IntegrationTestBase`. Containers come from **docker-compose** via
`com.avast.gradle.docker-compose`, not Testcontainers.

**Execution is split**, which is one deliberate difference from PCR — there, everything runs in `test`:

```bash
./gradlew test              # unit only, no Docker, ~6s
./gradlew integrationTest   # compose up -> run -> compose down, ~28s
./gradlew build             # both
```

`integrationTest` selects `**/integration/**` from the same source set; `test` excludes it. Bringing
the stack up and down is wired via `dockerCompose.isRequiredBy(integrationTest)`, so there is no
manual `composeUp`/`composeDown` sequencing to get wrong — **including on failure** (verified: a
failing `integrationTest` still runs `composeDown` and leaves no containers).

`composeUp` itself depends on `azureFunctionsPackage` and `prepareFunctionsHostSecrets`, so the app is
always packaged and the host keys seeded before the stack starts.

CI is therefore a single `./gradlew build`.

`docker-compose.yml` declares two services:

| Service | Image | Port | Role |
|---|---|---|---|
| `functions` | `mcr.microsoft.com/azure-functions/java:4-java25` | 7071→80 | The **packaged app** in the real runtime, app root bind-mounted from the `azureFunctionsPackage` staging dir |
| `wiremock` | `wiremock/wiremock:3.13.2` | 8089→8080 | Stands in for `service-cp-crime-results-pcr` |

The app under test reaches the stub over the compose network
(`PCR_SERVICE_INGESTION_ENDPOINT=http://wiremock:8080/internal/hearing-results`), and the tests
drive both over their published host ports.

This works without any Azure resources because the Event Grid trigger *is* an HTTP webhook the host
serves:

```
POST /runtime/webhooks/eventgrid?functionName=PrisonCourtRegisterHearingResulted&code=<system key>
aeg-event-type: Notification        # or SubscriptionValidation
[ { …EventGridSchema event… } ]
```

Six tests, covering what unit tests structurally cannot:

| Test | Verifies |
|---|---|
| app loads and registers | The packaged artefact actually boots in the real host — catches `lib/` prune regressions, `scriptFile`/version mismatches, Java 25 runtime problems |
| notification relayed verbatim | The binding really hands our `String` param the event, and the body reaching PCR is byte-identical inside a 1-element array |
| **handshake answered by the host** | `SubscriptionValidation` → `200` + `{"validationResponse": "<code>"}`, and it never reaches PCR. **This is the design's central claim** — see below |
| no `hearingId` → skipped | `202`, zero calls to PCR |
| `503` → retried then fails | 2 attempts, then `500` so Event Grid redelivers |
| `400` → not retried | exactly 1 attempt, then `500` |

Observed status codes, confirmed empirically rather than assumed: success and deliberate-skip both
return **202**, a failed invocation returns **500**, the handshake returns **200**.

Two things to know:

- **The system key.** `/runtime/webhooks/eventgrid` returns 401 without the `eventgrid_extension`
  system key, and `AZURE_FUNCTIONS_ENVIRONMENT=Development` does *not* waive it. The
  `prepareFunctionsHostSecrets` Gradle task seeds the host's file-based secret store with known test
  keys, generated into `build/` rather than committed so nothing key-shaped is ever checked in.
  `composeUp` depends on it.
- **Readiness.** `composeUp`'s TCP wait only proves the port is open — the .NET host binds it well
  before the Java worker has loaded the function. `FunctionsHostClient.awaitReady()` polls the webhook
  with an empty batch until it answers, and `IntegrationTestBase` calls it in `@BeforeAll`.

JaCoCo covers both suites: `jacocoTestReport` aggregates every `.exec` under `build/jacoco`, so
coverage is the union of the two runs rather than whichever ran last.

### Two Gradle-specific things worth knowing

**`azure-functions-java-library` is `implementation`, not `compileOnly`.** `azureFunctionsPackage`
scans the *runtime* classpath for `@FunctionName` entry points, and fails with
`ClassNotFoundException: com.microsoft.azure.functions.annotation.FunctionName` if the annotations
are compile-only. Maven's `provided` scope had no such problem.

**Consequence: a `pruneWorkerProvidedJars` task.** Because the library is now a runtime dependency,
the plugin stages it into `lib/` — and it does *not* exclude it itself (verified). The Functions Java
worker provides its own copy, and two copies on the classpath risk binding-resolution errors, so
`build.gradle` prunes it after packaging. `lib/` ends up with the same 7 jars the Maven build
produced. If you add another worker-provided dependency, add its pattern to
`workerProvidedJarPatterns`.

## Java 25

Targets **Java 25** (LTS) via a Gradle toolchain — the same version
`service-cp-crime-results-pcr` uses. Java 25 is GA on the Azure Functions Java runtime, supported
until May 2029. Verified locally on Temurin 25.0.2 (bytecode major version 69).

CI installs it with `actions/setup-java@v5` (`java-version: '25'`), so no bespoke agent image is
needed — this is why CI runs on GitHub Actions rather than the Azure DevOps pool, whose shared
identifiers are Java 21.

## CI

**GitHub Actions**, following `service-cp-crime-results-pcr`. There is no `azure-pipelines.yaml` —
it was removed, because the shared `context-verify` template drives Maven and the CPP pool offers
only Java 21 agents, so it could never have built this repo.

| Workflow | Trigger | Does |
|---|---|---|
| `ci-draft.yml` | PR + push to `main` | Calls `ci-build-deploy.yml`. Builds and verifies the zip on PRs; deploys only on push to `main` |
| `ci-released.yml` | Release published, or manual | Calls `ci-build-deploy.yml` with `is_release: true` |
| `ci-build-deploy.yml` | (reusable) | Artefact version → Gradle build + tests → package zip → verify zip → deploy |
| `code-analysis.yml` | PR | PMD against `.github/pmd-ruleset.xml` |
| `codeql.yml` | PR + weekly | CodeQL `security-extended` + CycloneDX SBOM artefact |
| `secrets-scanner.yml` | PR + weekly | `hmcts/secrets-scanner` |
| `auto-merge-dependabot.yml` | PR | Auto-approves and merges Dependabot PRs |

`dependabot.yml` tracks Gradle dependencies daily and Actions weekly, as in the PCR service.

The build job also runs a **zip verification step** that fails CI if the packaged artefact is wrong in
one of three ways that would otherwise only surface at runtime: a missing `eventGridTrigger` binding,
a `function.json` `scriptFile` that does not match the versioned jar actually in the zip, or the
worker-provided `azure-functions-java-library` having been shipped in `lib/`.

> **Deploy is not yet live.** The `Deploy` job needs an Entra app registration with a federated
> credential for this repo, Contributor (or Website Contributor) on the target Function App, and the
> secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`. Until those exist the job
> **skips cleanly with a warning** rather than failing or pretending to deploy. If the platform team
> would rather keep deployment in ADO, swap that job for `hmcts/trigger-ado-pipeline` against a
> Functions-capable pipeline.

## Deployment

Target environment **STE-CCP0121**, the slot ADR-007 names (`eg-ste-ccp0121-hearingres`). Coordinates
live in `build.gradle`'s `azurefunctions` block and follow the estate convention
`fa-<env>-ccpNNNN-<short>` inside `RG-<ENV>-CCPNNNN-HEARINGRES`:

| Setting | Value | Note |
|---|---|---|
| `appName` | `fa-ste-ccp0121-pcrrelay` | **Does not exist yet** — must be created |
| `resourceGroup` | `RG-STE-CCP0121-HEARINGRES` | Holds the 7 sibling hearing-resulted apps |
| `appServicePlanName` | `as-ste-ccp0121-hearingres` | **Existing shared plan**, EP3 ElasticPremium, Linux (`reserved: true`) — verified compatible with a Java 25 linux app, so no new plan is needed |
| `region` | `uksouth` | |

Reusable from the resource group: storage `sasteccp0121hearingres` (what siblings use for
`AzureWebJobsStorage`) and App Insights `ai-ste-ccp0121-hearingres`.

### One-off provisioning

```bash
az login
RG=RG-STE-CCP0121-HEARINGRES
APP=fa-ste-ccp0121-pcrrelay

az functionapp create -g $RG -n $APP \
  --plan as-ste-ccp0121-hearingres \
  --storage-account sasteccp0121hearingres \
  --runtime java --runtime-version 25.0 --functions-version 4 --os-type Linux \
  --app-insights ai-ste-ccp0121-hearingres \
  --tags environment=ste application=CP businessArea=crime builtFrom=Functionapp

az functionapp config appsettings set -g $RG -n $APP --settings \
  PCR_SERVICE_INGESTION_ENDPOINT="https://<pcr-service-ingress-host>/pcr/internal/hearing-results" \
  FORWARD_MAX_ATTEMPTS=3 FORWARD_RETRY_DELAY_IN_SECONDS=2
```

Three things in that first command will each fail the whole call if you get them wrong, and none of the
error messages point at the real fix:

| | Why |
|---|---|
| `--runtime-version 25.0`, not `25` | The CLI matches the version string exactly against `['25.0','21.0','17.0','11.0','8.0']` and rejects the bare major |
| `--tags` with all four | The `CppTagging` management-group policy **denies** creation without `environment`, `application`, `businessArea`, `builtFrom`. Values above are copied from the sibling apps in this resource group |
| `--os-type Linux` + Java | The shared plan `as-ste-ccp0121-hearingres` is Linux (`reserved: true`); OS cannot be mixed on one plan |

Permissions needed, beyond the obvious `Microsoft.Web/sites/write`:
`Microsoft.Storage/storageAccounts/listKeys/action` on `sasteccp0121hearingres` — the CLI reads the
account key to build `AzureWebJobsStorage`. Read-only or Website-Contributor-only roles fail here.

### Deploy

Two options. The second matches how the rest of the estate's function apps are deployed.

```bash
# a) via the Gradle plugin (auth type azure_cli, so `az login` first)
./gradlew azureFunctionsDeploy

# b) zip deploy, the same command used for the legacy JS function apps
./gradlew azureFunctionsPackageZip -DARTEFACT_VERSION=0.0.1
az functionapp deployment source config-zip \
  -g RG-STE-CCP0121-HEARINGRES \
  -n fa-ste-ccp0121-pcrrelay \
  --src build/azure-functions/fa-ste-ccp0121-pcrrelay.zip
```

`config-zip` is language-agnostic (Kudu ZipDeploy), and the zip this build produces is already the
right shape for it — `host.json` at the root, `PrisonCourtRegisterHearingResulted/function.json`, the
app jar, and `lib/`.

> **The Azure Portal cannot deploy this app's code.** Java has no in-portal editing — the Functions
> language-support matrix lists Java as Linux ✓ / Windows ✓ / in-portal editing ✗; only script
> languages (JS, Python, PowerShell) get the editor. Creating the *resource* in the Portal is fine;
> the code must come from zip deploy, the Gradle plugin, or CI.

> **If you create the app by hand, set the runtime to Java 25 on Linux.** `config-zip` ships content
> only — it does not set `FUNCTIONS_WORKER_RUNTIME=java` or `linuxFxVersion=JAVA|25`. An app created as
> Node (the obvious default in a resource group where all seven siblings are Node) will accept the
> deploy, report success, and never fire the function.

### Wire the Event Grid subscription

Mirrors `egs-prison-court` exactly — `AzureFunction` destination (no webhook, no validation handshake,
no key in a URL), filtered to `Hearing_Resulted`:

```bash
TOPIC=$(az eventgrid topic show -n eg-ste-ccp0121-hearingres -g $RG --query id -o tsv)
az eventgrid event-subscription create \
  --name egs-pcr-relay \
  --source-resource-id "$TOPIC" \
  --endpoint-type azurefunction \
  --endpoint "$(az functionapp show -g $RG -n $APP --query id -o tsv)/functions/PrisonCourtRegisterHearingResulted" \
  --included-event-types Hearing_Resulted \
  --max-delivery-attempts 30 --event-ttl 1440
```

CI uses OIDC federated credentials rather than `az login`; `-DARTEFACT_VERSION=` sets the version
(default `0.0.999`), which CI supplies from `hmcts/artefact-version-action`.

### Still outstanding

- **`PCR_SERVICE_INGESTION_ENDPOINT`.** The path (`/pcr/internal/hearing-results` — ingress prefix
  `/pcr` plus the service's `POST /internal/hearing-results`) is settled and taken from the built
  `api-cp-crime-results-pcr` artefact rather than the design doc, which specifies a path that never
  shipped. The **host** is deliberately not recorded here: it is an internal ingress hostname and an
  infra detail held by the platform team, redacted for the same reason ADR-007 redacts it. Get the
  per-environment value from them, and confirm the PCR service is actually deployed and routing there
  before wiring the subscription — until then the relay starts but every event fails.
- **The `pcr-hearing-results` subscription ADR-007 describes as "already provisioned" does not exist**
  on `eg-ste-ccp0121-hearingres`. Thirteen subscriptions are present; none is it. So nothing currently
  delivers to the PCR service's webhook, and `egs-pcr-relay` above would be the first path to it.
- **Permissions.** `az functionapp show` on a sibling fails with `AuthorizationFailed` on
  `Microsoft.Web/sites/publishxml/action`, though reading app settings works — so deploy rights need
  confirming.
- **Provisioning route.** 30+ identically-shaped `*-HEARINGRES` resource groups imply central
  automation, and no IaC for them was found. Prefer having the platform team create the app rather than
  running `functionapp create` by hand, so it does not drift from whatever produces the rest.

## Branch strategy

Trunk-based on `main`, matching `service-cp-crime-results-pcr` — **not** the JGitFlow model the CPP
Maven repos use. There is no `dev/release` branch and no release-branch dance; releases are cut by
publishing a GitHub release, which triggers `ci-released.yml`.

Work on `feature/<something>` branches and merge via PR. Two rulesets enforce this:

| Ruleset | Applies to | Rules |
|---|---|---|
| `main` | default branch | No deletion, no force-push; PR with 1 approval and resolved threads; 5 required status checks |
| `feature-branches` | `feature/**` | No deletion, no force-push |

There are no bypass actors, so the PR requirement applies to everyone including admins.
