# Deployment runbook — non-local environments

Every command here has been run for real against **STE-CCP0121**, and every failure mode in
§7 is one that actually happened rather than one anticipated. Verified 7 Aug 2026.

> The internal ingress **hostnames** are deliberately not recorded in this repo. Get the
> per-environment value from the platform team. Everything else is here.

---

## 1. Prerequisites

| | |
|---|---|
| Azure CLI | 2.71+ (`az login`, correct subscription selected) |
| JDK | **25** — the Gradle toolchain pins it; an older JDK fails with "invalid target release: 25" |
| Docker | Only for `./gradlew build`'s integration tests. Not needed to package or deploy |
| Azure RBAC | See §1.1 — more than Website Contributor |
| CA bundle | `internal_ca_certs.pem` must be present in the repo root (see §4) |

### 1.1 Required permissions

More than you'd expect, and the failures are unhelpful. On the target resource group:

| Action | Needed for | Failure if missing |
|---|---|---|
| `Microsoft.Web/sites/write` | creating the app | `AuthorizationFailed` |
| `Microsoft.Web/sites/config/write` | app settings | `AuthorizationFailed` on `config/appsettings` |
| `Microsoft.Storage/storageAccounts/listKeys/action` | `az functionapp create` building `AzureWebJobsStorage` | fails at the **storage** step, not on `sites/write` — misleading |
| `Microsoft.Network/…/subnets/join/action` on the **VNet's** resource group | VNet integration | the VNet lives in a *different* RG (`RG-STE-INT-01`) |
| `Microsoft.EventGrid/eventSubscriptions/write` | the subscription | |

If access is PIM-elevated, **check it is still active before starting** — it expired mid-deployment
during this work, twice.

---

## 2. Environment topology

Naming follows the estate convention `fa-<env>-ccpNNNN-<short>` inside `RG-<ENV>-CCPNNNN-HEARINGRES`,
alongside the seven sibling hearing-resulted apps.

### 2.1 STE-CCP0121 — the only environment deployed today

| Resource | Value | New or existing |
|---|---|---|
| Function App | `fa-ste-ccp0121-pcrrelay` | **created by this work** |
| Resource group | `RG-STE-CCP0121-HEARINGRES` | existing |
| App Service plan | `as-ste-ccp0121-hearingres` | **existing, shared** — EP3 ElasticPremium, Linux (`reserved: true`) |
| Storage | `sasteccp0121hearingres` | existing, shared |
| App Insights | `ai-ste-ccp0121-hearingres` | existing, **shared with 7 siblings** |
| Event Grid topic | `eg-ste-ccp0121-hearingres` | existing |
| Event subscription | `egs-pcr-relay` | **created by this work** |
| VNet / subnet | `VN-STE-INT-01` / `sn-ste-ccp0121-courtreg` in **`RG-STE-INT-01`** | existing, shared |

Only the app and the subscription are new. Everything else is reused — so do **not** let the Gradle
plugin create a plan.

### 2.2 Other environments

Slots exist but **no relay is deployed in any of them**: `RG-DEV-CCP0101…0107`, `RG-SIT-CCP0101`,
`RG-NFT-CCP0101/0102`, `RG-STE-CCP0101…0122`. Substitute `<env>` and `ccpNNNN` throughout.

Two things to check per environment before starting:

- **The topic must exist.** Most slots have `eg-<env>-ccpNNNN-hearingres`, but not all — `CCP0106` has
  no topic, so a relay there would have nothing to subscribe to.
- **The plan's OS.** Java 25 needs Linux. Confirm with
  `az appservice plan show … --query reserved` → must be `true`. You cannot mix OS on one plan.

---

## 3. Environment-specific settings

Only three settings vary by environment. The rest are either created by Azure or identical everywhere.

| Setting | Varies? | Value | Notes |
|---|---|---|---|
| `PCR_SERVICE_INGESTION_ENDPOINT` | **yes** | `https://<ingress-host>/pcr/internal/hearing-results` | Path is ingress prefix `/pcr` + the service's `POST /internal/hearing-results`. Taken from the built `api-cp-crime-results-pcr` artefact, **not** from ADR-007, which documents a path that never shipped |
| `PCR_SERVICE_CA_BUNDLE_PATH` | no | `/home/site/wwwroot/internal_ca_certs.pem` | Where `stageInternalCaBundle` puts the bundle |
| `FORWARD_MAX_ATTEMPTS` | tune | `3` | In-process retries, *under* Event Grid's 30 |
| `FORWARD_RETRY_DELAY_IN_SECONDS` | tune | `2` | Keep `attempts × delay` well below the Function App timeout |
| `HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS` | no | `10` (default) | Optional |
| `HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS` | no | `30` (default) | Optional |
| `PCR_SERVICE_INGRESS_HEADER_NAME` / `_VALUE` | no | unset | The endpoint is `security: []`. Only set if an ingress in front of it demands a header |
| `FUNCTIONS_WORKER_RUNTIME` | no | `java` | Set by `functionapp create` |
| `FUNCTIONS_EXTENSION_VERSION` | no | `~4` | Set by `functionapp create` |
| `WEBSITE_RUN_FROM_PACKAGE` | **managed** | a **blob SAS URL** | Set by `azureFunctionsDeploy`. **Do not hand-edit** — see §7.4 |

Azure-managed, do not set by hand: `AzureWebJobsStorage`, `APPLICATIONINSIGHTS_CONNECTION_STRING`,
`WEBSITE_CONTENTAZUREFILECONNECTIONSTRING`, `WEBSITE_CONTENTSHARE`, `MACHINEKEY_DecryptionKey`,
`SCM_DO_BUILD_DURING_DEPLOYMENT`, `WEBSITES_ENABLE_APP_SERVICE_STORAGE`.

---

## 4. First-time provisioning

Set your target once:

```bash
ENV=ste; SLOT=ccp0121
RG=RG-$(echo $ENV | tr a-z A-Z)-$(echo $SLOT | tr a-z A-Z)-HEARINGRES
APP=fa-$ENV-$SLOT-pcrrelay
PLAN=as-$ENV-$SLOT-hearingres
STORAGE=sa${ENV}${SLOT}hearingres          # verify — naming is not perfectly regular
AI=ai-$ENV-$SLOT-hearingres
TOPIC_NAME=eg-$ENV-$SLOT-hearingres
```

### 4.1 Create the app

```bash
az functionapp create -g $RG -n $APP \
  --plan $PLAN \
  --storage-account $STORAGE \
  --runtime java --runtime-version 25.0 --functions-version 4 --os-type Linux \
  --app-insights $AI \
  --tags environment=$ENV application=CP businessArea=crime builtFrom=Functionapp
```

Three flags each fail the **whole** call, with errors that don't point at the fix:

- `--runtime-version 25.0`, **not** `25`. The CLI matches the string exactly against
  `['25.0','21.0','17.0','11.0','8.0']`.
- `--tags` with all four keys. The `CppTagging` management-group policy **denies** creation otherwise.
  The four required keys are exactly `environment`, `application`, `businessArea`, `builtFrom` — read
  from the policy definition, and the values above match the sibling apps.
- `--os-type Linux`, because the shared plan is Linux.

### 4.2 VNet integration — required, and easy to miss

Without this the app cannot route to the internal ingress and **every relay fails** with an I/O error
and no HTTP response. `functionapp create` does not do it.

```bash
SUBNET_ID=$(az resource show -g $RG -n fa-$ENV-$SLOT-prisoncourtreg \
  --resource-type Microsoft.Web/sites --query properties.virtualNetworkSubnetId -o tsv)

az functionapp vnet-integration add -g $RG -n $APP \
  --vnet "$(dirname "$(dirname "$SUBNET_ID")")" \
  --subnet "$(basename "$SUBNET_ID")"
```

Copying the subnet from a sibling is deliberate: all apps on one App Service plan must use the same
subnet, and the plan is already joined, so this consumes no extra addresses. Restarts the app.

### 4.3 Application settings

```bash
az functionapp config appsettings set -g $RG -n $APP --settings \
  PCR_SERVICE_INGESTION_ENDPOINT="https://<ingress-host>/pcr/internal/hearing-results" \
  PCR_SERVICE_CA_BUNDLE_PATH=/home/site/wwwroot/internal_ca_certs.pem \
  FORWARD_MAX_ATTEMPTS=3 \
  FORWARD_RETRY_DELAY_IN_SECONDS=2
```

`PCR_SERVICE_CA_BUNDLE_PATH` is **essential**. Without it the CA bundle ships but nothing reads it, and
you get a TLS failure identical to having no bundle at all — the most confusing failure in this whole
setup.

### 4.4 Confirm the CA bundle is present

```bash
ls -l internal_ca_certs.pem     # must exist in the repo root before packaging
```

If absent, the build **succeeds with a warning** and produces a package that cannot talk to PCR. That
is intentional so the future CI-fetch migration can land, but it means a missing bundle is easy to
overlook. See `README` → *Internal CA trust*.

---

## 5. Deploy

**Use the Gradle plugin.** `az functionapp deployment source config-zip` does not work on this app —
see §7.4.

```bash
az login
./gradlew azureFunctionsDeploy \
  -DARTEFACT_VERSION=0.0.2 \
  -DFUNCTION_APP_NAME=$APP \
  -DFUNCTION_RESOURCE_GROUP=$RG \
  -DFUNCTION_APP_SERVICE_PLAN=$PLAN
```

The plugin packages, stages the CA bundle, prunes the worker-provided jar, uploads to blob storage, and
points `WEBSITE_RUN_FROM_PACKAGE` at a SAS URL. It also applies the `azurefunctions` `appSettings`
block, so `PCR_SERVICE_CA_BUNDLE_PATH` is reasserted on every deploy.

Omit the `-D` overrides to use the STE-CCP0121 defaults from `build.gradle`.

### 5.1 Verify the function registered

```bash
az functionapp function list -g $RG -n $APP -o table   # expect PrisonCourtRegisterHearingResulted
```

Java cold-starts on Elastic Premium take up to a minute; retry before concluding failure. **This must
pass before §6** — Event Grid validates the target function exists when the subscription is created.

---

## 6. Wire the Event Grid subscription

The point of no return: live events start flowing immediately.

```bash
TOPIC=$(az eventgrid topic show -n $TOPIC_NAME -g $RG --query id -o tsv)
FN="$(az functionapp show -g $RG -n $APP --query id -o tsv)/functions/PrisonCourtRegisterHearingResulted"

az eventgrid event-subscription create \
  --name egs-pcr-relay \
  --source-resource-id "$TOPIC" \
  --endpoint-type azurefunction --endpoint "$FN" \
  --included-event-types Hearing_Resulted \
  --max-delivery-attempts 30 --event-ttl 1440
```

`--included-event-types Hearing_Resulted` is **required for correctness**, not tidiness. The topic also
carries `Hearing_Resulted_Complex`, which the PCR service rejects with a non-retryable 400. Verified
that the sibling subscriptions filter the same way and `Hearing_Resulted_Complex` goes to
`egs-nowsce-complex` instead.

**Add a dead-letter destination.** The siblings have none, so a permanently failing event is discarded
after 24h with no record:

```bash
  --deadletter-endpoint <storage-account-resource-id>/blobServices/default/containers/pcr-relay-deadletter
```

---

## 7. Verification and troubleshooting

### 7.1 Smoke test without waiting for a real hearing

The Event Grid trigger is an HTTP webhook, so you can inject an event directly:

```bash
KEY=$(az functionapp keys list -g $RG -n $APP --query "systemKeys.eventgrid_extension" -o tsv)
HID=$(uuidgen | tr 'A-Z' 'a-z')

curl -s -w "\nHTTP %{http_code}\n" \
  -X POST "https://$APP.azurewebsites.net/runtime/webhooks/eventgrid?functionName=PrisonCourtRegisterHearingResulted&code=$KEY" \
  -H 'Content-Type: application/json' -H 'aeg-event-type: Notification' \
  -d "[{\"id\":\"smoke-$HID\",\"subject\":\"hearing/resulted\",\"eventType\":\"Hearing_Resulted\",\"eventTime\":\"$(date -u '+%Y-%m-%dT%H:%M:%SZ')\",\"dataVersion\":\"1.0\",\"data\":{\"hearingId\":\"$HID\",\"hearingDay\":\"$(date -u '+%Y-%m-%d')\",\"userId\":\"00000000-0000-0000-0000-000000000000\"}}]"
```

This bypasses Event Grid, so it does not prove the subscription — but it proves the app, the CA trust
and the network path. To test the subscription too, publish to the topic instead; note that fans out to
**all** subscribers, so every sibling app will also process your synthetic event.

Then read the logs (filter hard — App Insights is shared with seven other apps):

```bash
AIID=$(az resource show -g $RG -n $AI --resource-type microsoft.insights/components --query properties.AppId -o tsv)
az monitor app-insights query --app "$AIID" --analytics-query \
  "traces | where timestamp > ago(15m) | where message has 'prisoncourtregister' | project timestamp, message | order by timestamp asc"
```

### 7.2 Reading the outcome

| Log line | Meaning |
|---|---|
| `Relayed to … status 2xx` | Working end to end |
| `Retryable status 500/503 …` | **TLS and networking are fine** — an HTTP round-trip happened. The problem is the PCR service |
| `I/O failure … ConnectException` | Never reached the service — VNet integration or DNS |
| `I/O failure … SSLHandshakeException` | Reached it, certificate rejected — CA bundle missing, wrong, or `PCR_SERVICE_CA_BUNDLE_PATH` unset |
| `No hearingId on event - skipping` | Working as designed, not an error |

That distinction is the fastest diagnostic here. Three separate causes produced an identical-looking
failure during the first deployment.

### 7.3 Expected status codes

`202` success or deliberate skip · `500` invocation failed (Event Grid will redeliver) · `200` +
`validationResponse` for the subscription-validation handshake, which the binding answers itself.

### 7.4 `config-zip` fails with a permanent 409

```
Deployment endpoint responded with status code 409
There may be an ongoing deployment or your app setting has WEBSITE_RUN_FROM_PACKAGE.
```

**Not transient — retrying will not clear it.** `azureFunctionsDeploy` sets
`WEBSITE_RUN_FROM_PACKAGE` to a blob SAS URL; while it holds a URL the app runs from that fixed blob
and Kudu ZipDeploy has nothing to update. The seven siblings use `WEBSITE_RUN_FROM_PACKAGE=1` and can
use `config-zip`; this app cannot. To switch, set it to `1` — and remember `config-zip` does not apply
the `appSettings` block, so `PCR_SERVICE_CA_BUNDLE_PATH` would then need setting by hand.

### 7.5 Other failures seen for real

| Symptom | Cause |
|---|---|
| `Invalid version: 25 for runtime java` from `az` | Use `--runtime-version 25.0` |
| `Invalid runtime configuration` from `azureFunctionsDeploy` | `build.gradle` wants `javaVersion = '25'`. The two tools want **opposite** formats — do not "align" them |
| `Resource … disallowed by policy … tagging requirements` | Missing `--tags` (§4.1) |
| `AuthorizationFailed` on `storageAccounts/listKeys` | Missing storage permission — fails at the storage step, not `sites/write` |
| Deploy succeeds, function never fires | App created as **Node**. Check `linuxFxVersion` is `Java|25` |
| Azure Portal cannot edit the code | Java has no in-portal editing. Portal can create the *resource* only |

---

## 8. Rollback

`WEBSITE_RUN_FROM_PACKAGE` points at a versioned blob, so the previous package still exists.

```bash
# fastest: stop processing without changing anything
az functionapp stop -g $RG -n $APP

# or redeploy a known-good version
./gradlew azureFunctionsDeploy -DARTEFACT_VERSION=<previous> -DFUNCTION_APP_NAME=$APP -DFUNCTION_RESOURCE_GROUP=$RG

# or detach the trigger, leaving the app deployed
az eventgrid event-subscription delete --name egs-pcr-relay --source-resource-id "$TOPIC"
```

Stopping the app does **not** stop Event Grid retrying: deliveries keep failing for up to 24h across 30
attempts, and with no dead-letter destination those events are lost. Deleting the subscription is the
only way to stop delivery cleanly.

---

## 9. Known state, 7 Aug 2026

Deployed and verified in STE-CCP0121: app loads on Java 25, function registered, Event Grid delivery
works, event parsed, **TLS to the PCR ingress succeeds**, retry/backoff and propagate-on-exhaustion
behave as designed.

**Outstanding:** the PCR service answers `500` — not the `503` its contract documents for "hearing
details not complete yet". That is the one remaining failure and belongs to that service.

No other environment has a relay deployed. See `docs/TODO-production-readiness.md`.
