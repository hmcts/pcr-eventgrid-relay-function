# TODO — production readiness

**As at 7 Aug 2026.** The relay works end to end in STE-CCP0121: Event Grid delivers, the event is
parsed, TLS to the PCR ingress succeeds, and failures retry then propagate so Event Grid redelivers.
Everything below is what stands between that and running in production.

Ordered by what blocks what, not by size. Items marked **P1** would cause data loss, silent failure or
an inability to deploy; **P2** would make an incident hard to diagnose; **P3** is debt.

---

## 1. Blockers

### 1.1 The PCR service cannot connect to Redis — P1, not ours to fix
The only failing hop, and now precisely diagnosed. `POST /pcr/internal/hearing-results` returns `500`
with a structured body:

```json
{"message":"Unable to connect to Redis",
 "traceId":"c5f6e6d5c2cba9e70cdc23eda453aecc",
 "timestamp":"2026-08-07T15:57:42Z"}
```

This also explains why it is a `500` and not the `503` the contract documents for "hearing details not
complete yet". That 503 path assumes Redis is *reachable* and the entry merely absent; here the
**connection itself** fails, which `GlobalExceptionHandler` maps to a generic 500. So the documented
not-ready path was never reached, and nothing about a synthetic `hearingId` is at fault.

- [ ] Give the PCR service team that `traceId` — it will appear in their logs
- [ ] Confirm Redis connectivity from the PCR service in this environment (`RedisInitialise` config,
      network path, credentials)
- [ ] Once Redis is reachable, re-run the relay smoke test (`docs/DEPLOYMENT.md` §7.1) and confirm the
      503 path is reachable — our retry/no-retry split assumes it exists and it has never been observed

**Owner:** PCR service team. Nothing to change in this repo.

Worth noting the relay behaves correctly under this failure: 500 is genuinely transient, so retrying is
right. But it means every event burns 3 in-process attempts *and* up to 30 Event Grid deliveries over
24h for as long as Redis is down — which is why 1.2 matters more than it first appeared.

### 1.2 No dead-letter destination — P1, silent data loss
Verified: `egs-pcr-relay` has `deadLetterDestination: null`. With 30 attempts over 24h, a permanently
failing event is **discarded with no record**. Given 1.1 this is actively happening: every event
relayed while Redis is down will exhaust its budget and vanish.

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

### 3.4 Never tested against a real hearing — P2, blocked on 1.1
Every test so far has used a synthetic event injected either at the webhook or at the topic. No real
`Hearing_Resulted` from `cpp-context-results` has traversed the relay.

**Do this after 1.1, not before.** With Redis unreachable, a real hearing fails identically to a
synthetic one, so the test would only re-observe the Redis error and prove nothing new about the relay.

- [ ] Drive a real hearing through STE-CCP0121 and confirm the full path
- [ ] Expect noise: the same event fans out to all seven siblings

---

## 4. Security and supply chain

### 4.1 CA bundle now lives in a GitHub secret — DONE, except history
The bundle's source of truth is the **`PCR_INTERNAL_CA_BUNDLE` repo secret**, set 10 Aug 2026 and
verified in CI: 4 certificates from the secret into the zip. Nothing certificate-shaped is tracked —
`.gitignore` excludes `.local/` and the bare filename at any path — and a set-but-unusable
`CA_BUNDLE_SOURCE` fails the build rather than falling back to anything.

- [x] Take the bundle from infrastructure rather than the repo
- [x] Set the `PCR_INTERNAL_CA_BUNDLE` secret — CI artefacts are deployable again

**Rotation is now a secret update plus a rebuild, no code change.** A repo-level secret suffices: the
bundle carries both live and non-live roots, so one value serves every environment, and the earliest
expiry is April 2028.

**Key Vault is not being pursued.** It would change only the one CI step (`azure/login` +
`az keyvault secret download` to the same path), needs the federated credential from 1.3, and buys
nothing while the secret works and nothing expires for years. Open item **7a** is closed on that basis;
revisit only if the platform team wants CA material centralised.

What remains is not about where the bundle lives — see 4.5 for the history exposure.

### 4.2 Secret scanning and push protection are off — P2
Blocked by an enterprise policy (`HTTP 422 — Contact your enterprise owner`), and **still blocked after
the repo went public**, so this is not a licensing question and cannot be fixed at repo level. The
reference repo `service-cp-crime-results-pcr` has secret scanning enabled, so this app is below the
estate's own bar. Given 4.5, note that GitHub will not alert on committed credentials here.

- [ ] Get an enterprise owner to enable them, or attach the org code-security configuration

### 4.3 Code scanning — RESOLVED by going public
Code scanning on a private repo needed GitHub Advanced Security, and an enterprise policy blocked
enabling it (`422 — An enterprise policy prevented modifying Code Security enablement`). Making the
repo public on 7 Aug 2026 removed the licence gate: `Analyze (java)` passes and CodeQL uploads results.

Worth recording, because it cost time: the `main` ruleset requires a status check named `CodeQL` as well
as `Analyze (java)`. That check is published by the **code-scanning feature itself**, not by
`codeql.yml`, so while code scanning was disabled it never reported and looked like a misconfigured
ruleset requiring a non-existent check. It was not — the ruleset was correct and both checks now pass.
Do not "fix" it by dropping required checks.

### 4.4 Two dependency bots — P3
Both `renovate.json` and `.github/dependabot.yml` are active, as in the PCR service. Expect duplicate
bump PRs.

- [ ] Consolidate on one, or accept and document it

### 4.5 The old CA bundle is in public git history — P1, already happened
The repo went public on 7 Aug 2026 while the previously-committed bundle was still in history. It is
retrievable from commits `7f3fce5` and `34f9563`, and two commit messages on published branches name an
internal ingress host. Removing it from the working tree (4.1) did not remove it from history, and any
existing clone or fork keeps it regardless.

Scope it accurately before deciding anything:

- **No key material.** The bundle is 4 `CERTIFICATE` blocks and zero `PRIVATE KEY` blocks. A CA
  certificate is a public object, presented in every TLS handshake to that ingress. This is **not** a
  key compromise and does not let anyone impersonate the ingress.
- **What it discloses** is internal PKI and naming topology — the certificate subjects name internal
  platform and ingress hosts, which is exactly what the PCR design doc redacts as "not for a public
  repo". Deliberately not repeated here.

So the question is not whether to rotate — rotating a CA does not un-publish a hostname — but whether
internal-name disclosure is acceptable for a public repo in this estate. Cheapest first:

- [ ] Delete the stale branches whose commit messages carry the host. Removes the most greppable copy
- [ ] Get a platform/security decision on whether the disclosure is acceptable. If it is, close this and
      drop the redaction convention from these docs, which currently contradicts the repo being public
- [ ] If it is not: repo goes private again and history is rewritten before further exposure. Note
      orphaned commits stay reachable via the API for a period even after branch deletion

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
1.1 PCR Redis connectivity ──▶ 3.4 real-hearing test ──▶ production readiness
1.2 dead-letter ──▶ 2.2 alerts
```

**The single highest-leverage item is 1.3.** It unblocks automated deployment, the Key Vault work, and
multi-environment promotion — and removes the dependence on time-boxed human elevation that blocked
this work twice.
