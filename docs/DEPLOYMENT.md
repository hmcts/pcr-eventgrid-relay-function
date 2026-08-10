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
| CA bundle | Not in the repo — no certificate material is tracked. Place it at `.local/internal_ca_certs.pem` (git-ignored), **or** set `CA_BUNDLE_SOURCE`. See §4.4 |

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

Only the app and the subscription are new; everything else is reused, so do **not** let the Gradle plugin
create a plan.

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

Only one setting genuinely varies. The full list of app settings is in `README` → *Configuration*; this
is what changes when you move environment.

| Setting | | |
|---|---|---|
| `PCR_SERVICE_INGESTION_ENDPOINT` | **varies** | `https://<ingress-host>/pcr/internal/hearing-results` — ingress prefix `/pcr` + the service's `POST /internal/hearing-results`. Taken from the built `api-cp-crime-results-pcr` artefact, **not** ADR-007, which documents a path that never shipped |
| `FORWARD_MAX_ATTEMPTS` / `_RETRY_DELAY_IN_SECONDS` | tune | `3` / `2`. In-process retries sit *under* Event Grid's 30; keep `attempts × delay` well below the Function App timeout |
| `PCR_SERVICE_CA_BUNDLE_PATH` | fixed | `/home/site/wwwroot/internal_ca_certs.pem` — where `stageInternalCaBundle` puts the bundle |
| `WEBSITE_RUN_FROM_PACKAGE` | **managed** | a blob SAS URL, set by `azureFunctionsDeploy`. **Do not hand-edit** — see §7.4 |

Everything else is either defaulted in code or created by `functionapp create`. Do not set
`AzureWebJobsStorage`, `APPLICATIONINSIGHTS_CONNECTION_STRING`, `WEBSITE_CONTENT*`,
`MACHINEKEY_DecryptionKey`, `SCM_DO_BUILD_DURING_DEPLOYMENT` or `WEBSITES_ENABLE_APP_SERVICE_STORAGE`
by hand.

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

Copying a sibling's subnet is deliberate: all apps on one plan must share it, and the plan is already
joined, so this consumes no extra addresses. Restarts the app.

### 4.3 Application settings

```bash
az functionapp config appsettings set -g $RG -n $APP --settings \
  PCR_SERVICE_INGESTION_ENDPOINT="https://<ingress-host>/pcr/internal/hearing-results" \
  PCR_SERVICE_CA_BUNDLE_PATH=/home/site/wwwroot/internal_ca_certs.pem \
  FORWARD_MAX_ATTEMPTS=3 \
  FORWARD_RETRY_DELAY_IN_SECONDS=2
```

`PCR_SERVICE_CA_BUNDLE_PATH` is **essential**: without it the bundle ships but nothing reads it, giving a
TLS failure indistinguishable from having no bundle at all. `azureFunctionsDeploy` reasserts it, but a
hand-created app will not have it.

### 4.4 Confirm the CA bundle is present

CI takes the bundle from the `PCR_INTERNAL_CA_BUNDLE` secret automatically. **Building locally, a fresh
clone has no bundle** — nothing certificate-shaped is committed — so get it from the platform team and
put it at `.local/internal_ca_certs.pem`, or point `CA_BUNDLE_SOURCE` at a copy. Never commit it.

Without either, the build **succeeds with a warning** and produces a package that cannot talk to PCR, so
check the artefact rather than the build log:

```bash
unzip -l build/azure-functions/*.zip | grep internal_ca_certs.pem
```

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

This packages, stages the CA bundle, prunes the worker-provided jar, uploads to blob storage and points
`WEBSITE_RUN_FROM_PACKAGE` at a SAS URL. It also applies the `appSettings` block, so
`PCR_SERVICE_CA_BUNDLE_PATH` is reasserted every deploy. Omit the `-D` overrides for the STE-CCP0121
defaults.

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

`--included-event-types Hearing_Resulted` is **required for correctness**: the relay does not filter, and
the topic also carries `Hearing_Resulted_Complex`, which PCR rejects with a non-retryable 400. (It
currently goes to `egs-nowsce-complex`, so the risk is theoretical — but it is config, so re-check it.)

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

This bypasses Event Grid, so it proves the app, the CA trust and the network path but **not** the
subscription. Publishing to the topic tests that too, but fans out to every sibling app as well.

Then read the logs — filter hard, App Insights is shared with seven other apps:

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

Start here. Three separate causes produced an identical-looking failure during the first deployment.

### 7.3 Expected status codes

`202` success or deliberate skip · `500` invocation failed (Event Grid will redeliver) · `200` +
`validationResponse` for the subscription-validation handshake, which the binding answers itself.

### 7.4 `config-zip` fails with a permanent 409

```
Deployment endpoint responded with status code 409
There may be an ongoing deployment or your app setting has WEBSITE_RUN_FROM_PACKAGE.
```

**Not transient — retrying will not clear it.** `azureFunctionsDeploy` sets
`WEBSITE_RUN_FROM_PACKAGE` to a blob SAS URL, and while it holds a URL the app runs from that fixed blob,
so Kudu ZipDeploy has nothing to update. The siblings use `WEBSITE_RUN_FROM_PACKAGE=1` and can use
`config-zip`; this app cannot. Setting it to `1` switches over — but `config-zip` does not apply the
`appSettings` block, so `PCR_SERVICE_CA_BUNDLE_PATH` would then need setting by hand.

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

## 9. Known state

Verified end to end in STE-CCP0121 — delivery, parsing, **TLS to the PCR ingress**, retry and
propagate-on-exhaustion. The one outstanding failure is the PCR service's own
(`Unable to connect to Redis`) and belongs to that service.

No other environment has a relay deployed. Current status and open work live in
`docs/TODO-production-readiness.md`, which is kept up to date; this section is not.

No other environment has a relay deployed. See `docs/TODO-production-readiness.md`.
