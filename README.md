# PCR Event Grid Relay Function

A **thin** Java Azure Function app. It listens for `Hearing_Resulted` Event Grid events and relays
each one, untouched, to the PCR service's ingestion endpoint. Nothing else.

```
Azure Event Grid                    this Function App                 service-cp-crime-results-pcr
topic: Hearing_Resulted  ──────▶  PrisonCourtRegisterHearingResulted  ──POST──▶  /internal/hearing-results
   (pointer event)                @EventGridTrigger                              (EventGridSchema array)
```

## Why this app exists

**Event Grid cannot deliver to the PCR service directly.** Webhook delivery needs an endpoint Event
Grid can reach over the public internet *and* whose TLS certificate it can validate against public
CAs. The PCR ingestion API sits behind an internal ingress presenting a **private-CA certificate** —
so it fails both tests, and no public CA chain exists that would let Event Grid trust it.

This relay closes that gap. It runs inside the VNet, so it can reach the internal ingress, and it adds
the private CA to its own trust store at runtime (`AdditiveTrust`) — something Event Grid itself has no
way to do. Event Grid delivers to a Function App it *can* validate; the PCR service receives an
ordinary internal HTTP call.

Two things fall out for free, which is why the accepted design
([ADR-007 / AMP-892](../service-cp-crime-results-pcr/docs/designs/2026-07-29-pcr-eventgrid-webhook-ingestion-design.md))
is better served this way than by the webhook-on-the-service it originally specified:

- the `Microsoft.EventGrid.SubscriptionValidationEvent` handshake is handled by the `@EventGridTrigger`
  binding, so **no handshake code is needed anywhere**;
- the Event Grid-shaped surface — a public HTTPS endpoint, and network isolation standing in for
  application-level auth (`security: []`) — stays out of the PCR service entirely.

> The handshake claim is **verified**, not assumed: `EventGridRelayIntegrationTest` sends a real
> `Microsoft.EventGrid.SubscriptionValidationEvent` to the real Functions host and asserts it answers
> `200 {"validationResponse": "<code>"}` without the event ever reaching the PCR service. If that
> test is ever deleted, this design's main justification becomes unevidenced again.

No durable task hub, no Redis, no orchestration state — this app holds no state at all. The private-CA
trust it needs is explained in *Internal CA trust* below.

## What this does NOT replace

**The legacy JavaScript durable-functions chain keeps running**, and this app is *not* a rewrite of it.
The `prisoncourtregister-azure-functions` module in
[`cpp-context-azure-legalaidagency`](../../cpp/cpp-context-azure-legalaidagency) already listens to the
same `Hearing_Resulted` event on its own subscription (design §4a), and §13 non-goals is explicit:
*"Changing or retiring existing email/post PCR distribution — additional channel, not a replacement"*.

This relay **adds** a channel. The two pipelines run side by side off the same topic:

```
                          Azure Event Grid — topic: Hearing_Resulted
                                        │
              ┌─────────────────────────┴──────────────────────────┐
              │ (existing subscription)             (egs-pcr-relay)
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

The legacy app is a **live dependency**, not dead weight — do not treat it as safe to switch off. See
`CLAUDE.md` → *What it does NOT do* and `docs/pipeline/initial-implementation/plan.md` §2a.

## The function

| `@FunctionName` | Trigger | Behaviour |
|---|---|---|
| `PrisonCourtRegisterHearingResulted` | Event Grid | Read `data.hearingId` for correlation → relay the envelope verbatim |

### What gets sent

`POST /internal/hearing-results`, the event verbatim in a single-element array:

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

Only `data.hearingId` is read, for correlation. Everything else passes through untouched, so the PCR
service needs no new contract — it receives exactly the request Event Grid would have made.

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

## Configuration

Copy `Azure/local.settings.sample.json` to `local.settings.json` in the repo root (git-ignored — it
holds keys, never commit it).

| Setting | Required | Default | Purpose |
|---|---|---|---|
| `PCR_SERVICE_INGESTION_ENDPOINT` | yes | — | Absolute URL of `POST /internal/hearing-results`. Missing/blank fails fast at startup |
| `PCR_SERVICE_CA_BUNDLE_PATH` | **in Azure** | — | PEM bundle of extra CAs to trust, added to the JVM defaults. The PCR internal ingress presents a private-CA certificate no JVM ships, so TLS fails without it. Unset locally (tests use plain HTTP) |
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

## Internal CA trust — why `internal_ca_certs.pem` is here

### Why it is needed

The PCR service sits behind an **internal ingress** whose TLS certificate is issued by a **private
CA** that no JVM ships in its default trust store. Without that CA, the relay cannot complete a TLS
handshake and every event fails — not with a `503` or a `404`, but with a bare I/O error and no HTTP
response at all.

This is easy to misdiagnose. When first deployed, the relay failed identically *before* and *after*
VNet integration was added; the logs said only `I/O failure … : null`, because `ConnectException` and
`SSLHandshakeException` both surface as `IOException` and the latter's message can be empty. It took a
comparison against the sibling apps' configuration to find the cause. (The logging now names the
exception type precisely so this is one step, not three.)

The Node sibling apps solve it with `NODE_EXTRA_CA_CERTS`, pointing at a PEM shipped in their
package. **The JVM has no equivalent** — it will not read a PEM from an environment variable — so the
trust store has to be assembled in code. `AdditiveTrust` does that, adding the bundle's CAs to the
platform defaults rather than replacing them.

Two non-options, recorded so they are not retried:

| Rejected | Why |
|---|---|
| `WEBSITE_LOAD_CERTIFICATES` | On Linux this exposes certificates as individual **DER** files under `/var/ssl/certs`. `AdditiveTrust` takes a single PEM bundle path and cannot consume a directory of DERs without further change |
| Importing into the JVM's `cacerts` | Requires a startup command mutating the JDK inside a managed Functions host. Fragile, and invisible to anyone reading the app config |

### How it is addressed — two sources, one staged path

`stageInternalCaBundle` stages the bundle into the deployment package at
`/home/site/wwwroot/internal_ca_certs.pem` — the same path and filename the siblings use, and what
`PCR_SERVICE_CA_BUNDLE_PATH` points at. Where the bundle comes *from* depends on `CA_BUNDLE_SOURCE`:

| `CA_BUNDLE_SOURCE` | Source used | If it is missing or not a PEM |
|---|---|---|
| set (CI) | that path | **build fails** |
| unset (local) | `.local/internal_ca_certs.pem` (git-ignored) | warns, packages without it |

CI materialises the bundle from the `PCR_INTERNAL_CA_BUNDLE` secret into `RUNNER_TEMP` — outside the
workspace, so it cannot be committed by accident — and exports `CA_BUNDLE_SOURCE`. The staged filename
is always `internal_ca_certs.pem` whatever the source file is called, because the app setting names
that exact path.

The asymmetry is deliberate. Locally a missing bundle is an inconvenience, and the integration tests
use plain HTTP so they do not need one. In CI, silently falling back to a committed certificate when
infrastructure was *supposed* to supply one would ship something nobody reviewed — so a set-but-broken
`CA_BUNDLE_SOURCE` fails the build rather than degrading. An unset GitHub secret expands to an empty
string, so the build also rejects a file containing no `BEGIN CERTIFICATE` block, and both
`verifyStagedApp` and the CI zip check assert the bundle actually reached the artefact.

### Where the bundle lives

**The `PCR_INTERNAL_CA_BUNDLE` GitHub secret is the source of truth.** It holds the PEM itself, not a
path. CI writes it to `$RUNNER_TEMP` — outside the workspace, so it cannot be committed by accident —
and points `CA_BUNDLE_SOURCE` at that file:

```
ci-build-deploy.yml
  └─ Build job
       1. Materialise the internal CA bundle   →  $RUNNER_TEMP/internal_ca_certs.pem
          (from the PCR_INTERNAL_CA_BUNDLE secret; plain PEM or base64)
          exports CA_BUNDLE_SOURCE
       2. ./gradlew build                      ← stageInternalCaBundle reads CA_BUNDLE_SOURCE
       3. ./gradlew azureFunctionsPackageZip
       4. Verify packaged zip                  ← asserts the bundle is in the artefact
```

Set, and verified in CI: 4 certificates from the secret into the zip. **CA rotation is now a secret
update plus a rebuild — no code change.** To rotate, `gh secret set PCR_INTERNAL_CA_BUNDLE < <pem>`.

`.local/internal_ca_certs.pem` remains only as a convenience for local builds and manual deploys, and
is git-ignored. It is a copy, not the source of truth — if the two ever disagree, the secret wins.

A repo-level secret is sufficient because the bundle carries **both live and non-live roots**, so one
value serves every environment. Earliest expiry is April 2028.

Key Vault was considered and is **not** being pursued: it would change only step 1
(`azure/login` + `az keyvault secret download` to the same path), needs the federated credential from
TODO 1.3, and buys nothing while the secret works and nothing expires for years. Revisit if the
platform team wants CA material centralised.

Still outstanding: the bundle was committed for a period, so it remains in **git history**. That is now
a live exposure rather than a future caveat — see TODO 4.1.

What it needs, and why it is not done yet:

1. **A source of truth** — a Key Vault secret (preferred, rotatable and access-controlled) or a GitHub
   Actions secret (simpler, but another copy to keep in step). The platform team owns this choice.
2. **Read access for CI** — the same Entra federated credential the `Deploy` job is waiting on, plus
   `get` on that secret. Worth requesting together rather than twice.
3. **Removing the committed bundle**, and deciding whether history needs rewriting before the repo
   could return to public.

Until (1) and (2) exist, the committed bundle is what makes the relay work at all — so it stays, with
this section as the record of why it is temporary.

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

## CI

**GitHub Actions**, following `service-cp-crime-results-pcr`. There is no `azure-pipelines.yaml` —
it was removed, because the shared `context-verify` template drives Maven and the CPP pool offers
only Java 21 agents, so it could never have built this repo. `actions/setup-java@v5` installs the
Java 25 the toolchain requires, with no bespoke agent image.

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

**Deploys are pipeline-only.** GitHub Actions builds and publishes the artefact; an Azure DevOps pipeline
deploys it, and promotion to a higher environment is a gated re-run of that pipeline against the same
artefact. See `docs/pipeline/hybrid-deploy/design.md` and `docs/DEPLOYMENT.md`.

`./gradlew azureFunctionsDeploy` is **decommissioned** — do not run it against a real app. The Gradle
tasks remain for `azureFunctionsPackage` and local `azureFunctionsRun`.

#### Why the two mechanisms cannot coexist

`WEBSITE_RUN_FROM_PACKAGE` decides where the host loads code from, and the two routes set it
incompatibly:

| Route | `WEBSITE_RUN_FROM_PACKAGE` | Code loaded from |
|---|---|---|
| `azureFunctionsDeploy` (decommissioned) | a **blob SAS URL** | that fixed blob |
| Kudu zip deploy — pipeline, `functions-action`, `config-zip` | `1` | Kudu's `SitePackages` |

While the setting holds a URL there is nothing for Kudu to update, so a zip deploy fails **permanently**:

```
Deployment endpoint responded with status code 409
There may be an ongoing deployment or your app setting has WEBSITE_RUN_FROM_PACKAGE.
```

The seven Node siblings use `1`, so they were always on the Kudu route; this app is the odd one out and
has to be cut over. Flipping the setting to `1` on its own would point the host at an **empty**
`SitePackages` and leave the app with no code — see the design doc §4.1 for the safe sequence.

That 409 is **permanent, not transient** — retrying will not clear it. An earlier revision of this
README recommended `config-zip` as the primary route on the grounds that it matches the siblings; that
was written before the app existed and is wrong for it.

To switch to `config-zip` instead you would have to set `WEBSITE_RUN_FROM_PACKAGE=1`, matching the
siblings. Worth doing if consistency with them matters more than the plugin's convenience — but note
the plugin also applies the `azurefunctions` `appSettings` block on every deploy, which `config-zip`
does not, so `PCR_SERVICE_CA_BUNDLE_PATH` would then need setting by hand.

The zip itself is fine either way — `host.json` at the root,
`PrisonCourtRegisterHearingResulted/function.json`, the app jar, `lib/`, and `internal_ca_certs.pem`.

> **The Azure Portal cannot deploy this app's code.** Java has no in-portal editing — the Functions
> language-support matrix lists Java as Linux ✓ / Windows ✓ / in-portal editing ✗; only script
> languages (JS, Python, PowerShell) get the editor. Creating the *resource* in the Portal is fine;
> the code must come from zip deploy, the Gradle plugin, or CI.

> **If you create the app by hand, set the runtime to Java 25 on Linux.** Neither deploy mechanism
> sets `FUNCTIONS_WORKER_RUNTIME=java` or `linuxFxVersion=JAVA|25`. An app created as Node (the obvious
> default in a resource group where all seven siblings are Node) will accept the deploy, report
> success, and never fire the function.

#### What has been verified in STE

As of 7 Aug 2026, deployed to `fa-ste-ccp0121-pcrrelay` and exercised by POSTing a synthetic event to
`/runtime/webhooks/eventgrid`:

| | |
|---|---|
| App loads on Java 25, function registered | ✅ |
| Event Grid delivery via `egs-pcr-relay` | ✅ (verified separately by publishing to the topic) |
| Event parsed, `hearingId` extracted | ✅ |
| **TLS to the PCR ingress** | ✅ — the private-CA trust works |
| Retry/backoff and propagate-on-exhaustion | ✅ 3 attempts, then 500 so Event Grid redelivers |
| PCR service returns success | ❌ — it answers **500** |

The remaining failure is the PCR service's, not this relay's: it answers `500` with
`{"message":"Unable to connect to Redis"}`. That also explains why it is not the `503` its contract
documents for "hearing details not complete yet" — that path assumes Redis is reachable and the entry
merely absent, whereas here the connection itself fails.

Diagnostic value of the log line: `Retryable status 500 from …` means the handshake completed and an
HTTP round-trip happened. `I/O failure … ConnectException` would mean the request never arrived — the
two are easy to conflate and the distinction is the fastest way to tell a network/TLS problem from a
service problem.

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

## Documentation

| Document | Covers |
|---|---|
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Step-by-step runbook for non-local environments — required permissions, per-environment settings, provisioning, VNet integration, deploy, Event Grid wiring, smoke testing, rollback, and every failure mode hit for real |
| [`docs/TODO-production-readiness.md`](docs/TODO-production-readiness.md) | What stands between the current STE deployment and production, prioritised, with a dependency map |
| [`docs/pipeline/initial-implementation/plan.md`](docs/pipeline/initial-implementation/plan.md) | Design decisions, rationale, corrections, and open items |

The Deployment section below is the summary; `docs/DEPLOYMENT.md` is the operational detail.

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
