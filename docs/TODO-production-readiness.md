# TODO — production readiness

**As at 7 Aug 2026.** The relay works end to end in STE-CCP0121: Event Grid delivers, the event is
parsed, TLS to the PCR ingress succeeds, and failures retry then propagate so Event Grid redelivers.
Everything below is what stands between that and running in production.

Ordered by what blocks what, not by size. Items marked **P1** would cause data loss, silent failure or
an inability to deploy; **P2** would make an incident hard to diagnose; **P3** is debt.

---

## 1. Blockers

### 1.1 The PCR service returns 500 — P1, not ours to fix
The only failing hop. A synthetic `hearingId` produces `500` from
`POST /pcr/internal/hearing-results`, where the contract documents **503** for "hearing details not
complete yet". So the service is erroring rather than taking its documented not-ready path.

Until this is understood, nothing downstream of the relay works, and the relay will burn its full retry
budget on every delivery.

- [ ] Check `service-cp-crime-results-pcr` logs for the 500 on an unknown hearing
- [ ] Confirm whether 503 is reachable at all, since our retry/no-retry split assumes it
- [ ] Re-test with a **real** `hearingId` present in Redis, which may behave differently

**Owner:** PCR service team.

### 1.2 No dead-letter destination — P1, silent data loss
Verified: `egs-pcr-relay` has `deadLetterDestination: null`. With 30 attempts over 24h, a permanently
failing event is **discarded with no record**. Given 1.1, that is not hypothetical.

The seven siblings also have none, so this is a deliberate deviation to argue for rather than an
oversight to copy.

- [ ] Create a blob container for dead letters
- [ ] `az eventgrid event-subscription update … --deadletter-endpoint <container-resource-id>`
- [ ] Add an alert on `DeadLetteredCount > 0` (see 2.2) — a dead-letter store nobody watches is not much better than none

### 1.3 Deploy depends on time-boxed human elevation — P1
Deployment today needs a person with PIM-elevated rights running Gradle from a laptop. During this
work that access expired mid-task and blocked deployment twice.

- [ ] Provision the Entra app registration + federated credential for this repo
- [ ] Add `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_SUBSCRIPTION_ID`
- [ ] Grant it Contributor (or Website Contributor + the storage `listKeys` action) on the app
- [ ] Confirm the `Deploy` job actually deploys rather than skipping

Same request as 4.1, so raise them together. Open item **2a**.

### 1.4 One environment only — P1 for a production path
Verified: exactly one `*pcrrelay*` app exists, in STE-CCP0121. There is no DEV, SIT, NFT or production
instance, and no promotion path.

- [ ] Decide the target environments and their `ccpNNNN` slots
- [ ] Parameterise `appName` / `resourceGroup` per environment — already supported via
      `-DFUNCTION_APP_NAME` / `-DFUNCTION_RESOURCE_GROUP`, and `ci-build-deploy.yml` accepts them as inputs
- [ ] Decide whether the Event Grid subscription is created per environment by hand or by IaC (see 3.3)

---

## 2. Observability

### 2.1 No diagnostic settings — P2
Verified: zero diagnostic settings on the app. Logs live only in App Insights
(`ai-ste-ccp0121-hearingres`, shared with the seven siblings), with default retention.

- [ ] Route `FunctionAppLogs` to Log Analytics
- [ ] Confirm retention meets whatever the audit requirement is for hearing data references

Note the shared App Insights is a real diagnostic hazard: during testing, one synthetic event produced
log lines from our relay *and* from `HearingResultedProbationOrchestrator`,
`InformantRegisterEventGridTrigger` and others. Any query must filter on
`uk.gov.moj.cpp.prisoncourtregister` or the operation name.

### 2.2 No alerts at all — P2
Verified: zero metric alerts scoped to the app. Nothing would tell anyone the relay had stopped
working. Given the topic was idle for 7+ days across every environment, absence of traffic is normal
and cannot itself be the signal.

- [ ] `DeadLetteredCount > 0` on the subscription — the single most valuable alert
- [ ] `DeliveryAttemptFailCount` sustained above a threshold
- [ ] Function `FailedRequests` / exception rate
- [ ] Decide who receives them; the repo has no support model (`README` of the PCR service records
      "Support model / on-call / escalation path: TBD" — this app inherits that gap)

### 2.3 No correlation beyond `hearingId` — P3
Logs are prefixed `[Hearing ID: …]`, which matches the estate convention and is genuinely useful. But
nothing ties a relay attempt to the PCR service's own processing of the same event.

- [ ] Consider propagating a correlation id / `traceparent` header, if the PCR service will read one

---

## 3. Reliability and correctness

### 3.1 Duplicate deliveries are not handled — P2
Event Grid guarantees *at-least-once*. The relay is a pure passthrough so it is safe in itself, but it
will happily forward the same event repeatedly, and after 1.1 is fixed the PCR service will receive
duplicates during any retry storm.

- [ ] Confirm `ResultsIngestionService` is idempotent per `(hearingId, hearingDay)`
- [ ] If not, that is a PCR-service change, not a relay one — resist adding dedup state here

### 3.2 Retry budgets multiply — P2
In-process retries (`FORWARD_MAX_ATTEMPTS` × `FORWARD_RETRY_DELAY_IN_SECONDS`) sit *under* Event Grid's
30 attempts over 24 hours. Currently 3 × 2s inside up to 30 deliveries.

- [ ] Sanity-check the product against the Function App timeout under a real outage
- [ ] Consider whether 30 × 24h is right for this event, or whether failing faster to dead-letter is better

### 3.3 All infrastructure is hand-made — P2
The app, its VNet integration and the Event Grid subscription were created by hand with `az`. Nothing
is in code. 30+ structurally identical `*-HEARINGRES` resource groups imply the siblings come from
automation this app is not part of.

- [ ] Establish whether that automation exists and can adopt this app
- [ ] Otherwise capture app + VNet integration + subscription + dead-letter in Terraform/Bicep
- [ ] Until then, treat `README` → *Deployment* as the only record of how to recreate it

### 3.4 Never tested against a real hearing — P2
Every test so far has used a synthetic event injected either at the webhook or at the topic. No real
`Hearing_Resulted` from `cpp-context-results` has traversed the relay.

- [ ] Drive a real hearing through STE-CCP0121 and confirm the full path
- [ ] Expect noise: the same event fans out to all seven siblings

---

## 4. Security and supply chain

### 4.1 CA bundle is committed — P2
`internal_ca_certs.pem` is in the repo, which is why the repo is private. Consequences: CA rotation is
a code change, the certificates are in git history permanently, and repo visibility is coupled to a
certificate.

- [ ] Move the bundle to Key Vault and have CI fetch it before `azureFunctionsPackageZip`
- [ ] Delete the committed copy
- [ ] Decide whether history needs rewriting before the repo could return to public

`stageInternalCaBundle` already copies whatever bundle is present and warns when absent, so **no
build-script change is needed** — only a CI step. Needs the same federated credential as 1.3. Open
item **7a**; full rationale in `README` → *Internal CA trust*.

### 4.2 Secret scanning and push protection are off — P2
Blocked by an enterprise policy (`HTTP 422 — Contact your enterprise owner`). The reference repo
`service-cp-crime-results-pcr` has both enabled, so this app is below the estate's own bar.

- [ ] Get an enterprise owner to enable them, or attach the org code-security configuration

### 4.3 CodeQL on a private repo needs GHAS — P2
The repo was public when CodeQL was set up. Code scanning on private repos requires GitHub Advanced
Security.

- [ ] Confirm GHAS covers this repo; if not, `codeql.yml` will fail on licensing rather than on findings

### 4.4 Two dependency bots — P3
Both `renovate.json` and `.github/dependabot.yml` are active, as in the PCR service. Expect duplicate
bump PRs.

- [ ] Consolidate on one, or accept and document it

---

## 5. Contract and design debt

### 5.1 ADR-007 is now partly wrong — P2
That design has Event Grid delivering **directly** to a webhook on the PCR service. This relay changes
that, and two of the design's statements are factually incorrect: the `pcr-hearing-results`
subscription it describes as "already provisioned" does not exist, and the endpoint path it specifies
(`/internal/pcr/hearingResults`) is not what shipped (`/internal/hearing-results`).

- [ ] Raise an ADR in the PCR repo recording the relay decision and superseding the direct-webhook part
- [ ] Correct the path and the subscription claim in the design doc
- [ ] Decide the fate of `HearingResultedWebhookController` — with the relay in place, nothing else calls it

Open items **4c**, **5**, **8**.

### 5.2 `eventType` filtering relies on subscription config — P3
The relay deliberately does not filter on `eventType`; correctness depends on `egs-pcr-relay` being
filtered to `Hearing_Resulted`. Verified correct today, and `Hearing_Resulted_Complex` goes to a
different subscription.

- [ ] Re-verify per environment when 1.4 creates new subscriptions — this is config, so it can drift

### 5.3 `resourceGroup` default is inherited from the legacy LAA app — P3
`build.gradle` defaults `RG-STE-CCP0121-HEARINGRES`, which is right for STE but hardcodes one
environment as the default.

- [ ] Once 1.4 defines environments, decide whether a default is wise at all

---

## 6. Quick wins

- [ ] Merge PR #7 — the CA bundle is deployed to STE but not on `main`, so `main` cannot currently
      produce a working artefact
- [ ] Delete the stale `feature/docs-and-deploy-corrections` and `feature/private-ca-trust` branches
      (needs a temporary ruleset bypass — they carry an unscrubbed commit message)
- [ ] Add a `SECURITY.md`, which the PCR service has and this repo lacks
- [ ] Record an owning team and support model in `README`

---

## Dependency map

```
1.3 federated credential ──┬──▶ 1.4 multi-environment deploy
                           └──▶ 4.1 CA bundle from Key Vault ──▶ repo can return to public
1.1 PCR 500 ──▶ 3.4 real-hearing test ──▶ production readiness
1.2 dead-letter ──▶ 2.2 alerts
```

**The single highest-leverage item is 1.3.** It unblocks automated deployment, the Key Vault work, and
multi-environment promotion — and removes the dependence on time-boxed human elevation that blocked
this work twice.
