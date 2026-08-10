# Deployment pipeline — GitHub Actions builds, Azure DevOps deploys

**Status:** accepted, not yet implemented. Blocked on a platform-team dependency (§6).

**Decision.** The build stays in GitHub Actions. Deployment moves to a thin Azure DevOps pipeline that
pulls a published artefact and pushes it to a Function App. Promotion to each higher environment is a
gated re-run of that same pipeline against the same artefact.

---

## 1. Why this split

| | |
|---|---|
| **Build in GHA** | Not really a choice. The CPP ADO pool offers **Java 21** agents and its shared `context-verify` template drives **Maven**; this repo is Gradle on **Java 25**. `azure-pipelines.yaml` was removed for exactly this reason. `actions/setup-java@v5` installs Java 25 with no bespoke agent image |
| **Deploy in ADO** | Governance. Service connections, environment approvals and the change-management audit trail are centralised in ADO for this estate. Not a technical preference — a technical read alone would keep deploys in GHA too |

The cost of the split is a handoff: the artefact must exist somewhere ADO can fetch it, and the version
must be passed across. §3 and §4 are about making that handoff boring.

## 2. What cannot be reused

`service-cp-crime-results-pcr` already does GHA-build → ADO-deploy, and its wiring is the model for the
GHA side. But **neither of its pipelines applies here**:

| Pipeline | What it does | Why not for us |
|---|---|---|
| **460** `cp-gh-artifact-to-acr` | Maven artefact → container image → ACR | No image. A Functions zip is not containerised |
| **434** | Helm release → AKS | No chart, no cluster |

So the ADO side is a **new, Functions-capable pipeline**. That is the one thing this design cannot
deliver from this repo (§6).

What *is* reusable is the **trigger** mechanism: `hmcts/trigger-ado-pipeline@v2` and
`hmcts/monitor-ado-pipeline@v1`, authenticated with `HMCTS_CP_ADO_PAT`. The artefact **download** is not
reusable — see §3.1.

## 3. Architecture

```
PR                    GHA: build · unit + integration tests · verify zip          (no deploy)

merge to main         GHA: build ONCE
                        ├─ publish artefact (immutable, versioned)  ── see §3.1
                        └─ trigger ADO deploy pipeline → DEV                     (automatic)

promote (button)      ADO: re-run the same pipeline, same artefact version
                        ├─ SIT   ┐
                        ├─ NFT   ├─ ADO Environment approval gate per stage
                        ├─ STE   │
                        └─ PROD  ┘
```

**Build once, deploy many.** The artefact is never rebuilt for a higher environment, so what was tested
in DEV is bit-for-bit what reaches production. This already almost holds:

| | |
|---|---|
| CA bundle baked into the zip | ✅ one bundle carries live *and* non-live roots (verified) — environment-agnostic |
| `PCR_SERVICE_INGESTION_ENDPOINT` | ✅ an app setting, not in the zip. The only value that genuinely varies |
| `appSettings` in `build.gradle` | ❌ **breaks it.** The Gradle plugin asserts app settings and needs `appName`/`resourceGroup` at deploy time, coupling the artefact to one app. Must move to IaC (§5) |

### 3.1 Where the artefact lives

**A GitHub Release asset**, versioned by `artefact-version-action`.

Actions artifacts (`actions/upload-artifact`, what the Build job produces today) are **not** suitable:
they are run-scoped and expire (90 days by default), so there is nothing durable to promote.

One consequence for the ADO side. `service-cp-crime-results-pcr` hands ADO pipeline 460 a set of
**Maven coordinates** — `GROUP_ID` / `ARTIFACT_ID` / `ARTIFACT_VERSION` — because it applies
`maven-publish` and pushes its jar to GitHub Packages. (That is a repository *format*, nothing to do with
the Maven build tool; both repos are Gradle-only.) This repo does not do that, and should not start:
`gradle/repositories.gradle` records the deliberate decision that there is **no `publishing` block**
because the deliverable is a zip, not a library, and a zip in a Maven coordinate would be a contortion.

So the new pipeline (§6) needs a **GitHub release download** step — a `GitHubRelease` task, or `gh`/`curl`
with a PAT — rather than reusing 460's artefact-download plumbing. That is a few lines, and it is the
price of not distorting how this repo publishes.

## 4. Changes needed in this repo

All in `.github/workflows/ci-build-deploy.yml` unless noted.

1. **Publish the zip as a release asset** on push to `main` and on release. Version comes from
   `hmcts/artefact-version-action`, already wired: draft versions on `main`, release versions on a
   published release. Draft builds become pre-releases so `main` merges are promotable without cluttering
   the release list.

2. **Replace the `Deploy` job.** It currently uses `Azure/functions-action`, which is a Kudu zip deploy.
   That job must go, or two deploy paths exist. Replace with:

   ```yaml
   Trigger-Deploy:
     needs: [Artefact-Version, Build]
     if: ${{ inputs.trigger_deploy }}
     runs-on: ubuntu-latest
     steps:
       - name: Trigger ADO deploy pipeline
         id: trigger
         uses: hmcts/trigger-ado-pipeline@v2
         with:
           pipeline_id: <NEW — see §6>
           ado_pat: ${{ secrets.HMCTS_ADO_PAT }}
           template_parameters: >
             {
               "env": "${{ inputs.environment }}",
               "artefactVersion": "${{ needs.Artefact-Version.outputs.artefact_version }}",
               "targetRepository": "${{ github.repository }}"
             }
       - name: Wait for it
         uses: hmcts/monitor-ado-pipeline@v1
         with:
           pipeline_id: <NEW>
           run_id: ${{ steps.trigger.outputs.run_id }}
           ado_pat: ${{ secrets.HMCTS_ADO_PAT }}
           poll_interval: 30
           timeout: 1800
   ```

   Deliberately **not** passing the Function App name or resource group: ADO owns the environment→app
   mapping, so adding an environment does not touch this repo.

3. **Add `HMCTS_CP_ADO_PAT`** as a repo secret, threaded through `ci-draft.yml` and `ci-released.yml` as
   `HMCTS_ADO_PAT` — the same shape the PCR service uses.

4. **Fix `ci-released.yml`,** which hardcodes `environment: dev`. A published release deploying to dev
   means there is nothing to promote *to*. It should take the environment as an input.

5. **Drop `AZURE_CLIENT_ID` / `AZURE_TENANT_ID` / `AZURE_SUBSCRIPTION_ID`** from the workflows once ADO
   owns deployment. GHA no longer needs Azure credentials at all, which removes the federated-credential
   work (TODO 1.3) from the critical path — ADO's existing service connections replace it.

### 4.1 Cut-over: the one step that can take the app down

**Deploys are pipeline-only.** `azureFunctionsDeploy` from a laptop is decommissioned — the two
mechanisms are mutually exclusive, because the Gradle plugin sets `WEBSITE_RUN_FROM_PACKAGE` to a blob
SAS URL and Kudu zip deploy refuses to run while it holds a URL (permanent 409, `DEPLOYMENT.md` §7.4).

The cut-over is **not** a preparatory step that can be done early:

```
today   WEBSITE_RUN_FROM_PACKAGE = https://…blob…/<versioned-package>?<sas>   ← app runs from here
        Kudu SitePackages = EMPTY. This app has only ever been deployed by the Gradle plugin.

set it to 1 on its own  →  host looks in SitePackages  →  nothing there  →  app has NO CODE
```

So sequence it with the deploy, not before it:

1. The new ADO pipeline exists and is proven against a **throwaway app first**, not this one.
2. Then, in one operation: set `WEBSITE_RUN_FROM_PACKAGE=1` and immediately run the pipeline's zip
   deploy. `AzureFunctionApp@2` with `zipDeploy` populates `SitePackages` and manages the setting itself,
   so in practice letting the pipeline's first run do both is the safest form.
3. Expect a short window with no code, and do it outside any period when events matter. Deleting the
   Event Grid subscription first (`DEPLOYMENT.md` §8) stops deliveries being lost during it.

Rollback if the cut-over fails: put `WEBSITE_RUN_FROM_PACKAGE` back to the SAS URL recorded above — the
versioned blob still exists — and the app runs the previous package again.

## 5. Terraform boundary

Terraform is **not needed to deploy** — a deploy is a zip push. It is needed for everything the deploy
assumes already exists, all of which is hand-made today (TODO 3.3):

| | Consequence of leaving it manual |
|---|---|
| Function App + the four `CppTagging` tags | creation is *denied by policy* without them |
| **VNet integration** | silently omitted → every relay fails with a bare I/O error. The easiest thing to forget |
| App settings per environment | the only genuinely varying value, typed by hand |
| Event Grid subscription `--included-event-types Hearing_Resulted` | **correctness**, not tidiness — drift sends unfilterable events at PCR (TODO 5.2) |
| Dead-letter destination | absent today → permanently failing events vanish after 24h (TODO 1.2) |
| Diagnostic settings, alerts | TODO 2.1 / 2.2 — nothing would report the relay stopping |

**Keep it in a separate pipeline from the app.** Terraform changes rarely and needs `plan` review; app
deploys happen on every merge. Coupling them makes every code change carry infrastructure risk.

**Check before writing.** There are 30+ structurally identical `*-HEARINGRES` resource groups, so the
sibling apps were almost certainly provisioned by existing automation. Establish whether it exists and can
adopt this app, and whether HMCTS already publishes Function App modules, before authoring one.

## 6. Platform-team dependency

This design cannot complete without a **new Functions-capable ADO pipeline**, since 460 and 434 do not
apply (§2). The ask:

- A pipeline (likely in `hmcts/cpp-azure-devops-templates`, where `gh-artifact-to-acr.yaml` lives) that
  takes `env` and `artefactVersion`, downloads the named release asset from this repo, and runs an Azure
  Functions zip deploy against the app for that environment.
- **ADO Environments** for `dev`/`sit`/`nft`/`ste`/`prod` with required approvers on everything above
  `dev` — this is the "push of a button" promotion, and where the audit trail comes from.
- The environment → (subscription, resource group, app name) mapping held in ADO, not here.
- Its pipeline ID, to fill in §4.

Sketch of the deploy step, for the conversation rather than as a finished artefact:

```yaml
parameters:
  - name: env
  - name: artefactVersion
steps:
  - task: AzureFunctionApp@2
    inputs:
      azureSubscription: $(serviceConnection)      # per-environment, held in ADO
      appType: functionAppLinux
      appName: $(functionAppName)
      package: $(Pipeline.Workspace)/pcr-eventgrid-relay-function.zip
      deploymentMethod: zipDeploy                  # needs WEBSITE_RUN_FROM_PACKAGE=1 (§4.1)
```

## 7. Open questions

- Which environments are in scope, and their `ccpNNNN` slots? Only STE-CCP0121 exists today; six DEV
  slots have topics, CCP0106 has none (TODO 1.4).
- Release asset or GitHub Packages for the handoff (§3.1)?
- Does existing sibling automation provision these apps, and can it adopt this one (§5)?
- ~~Does anything still need `azureFunctionsDeploy`?~~ **Resolved: no.** Deploys are pipeline-only. The
  task remains in the build for `azureFunctionsPackage`/`azureFunctionsRun`, but must not be used against
  a real app once cut over.
- **App settings now have no owner.** The `azurefunctions { appSettings { … } }` block in `build.gradle`
  is only applied by `azureFunctionsDeploy`, so with that route gone nothing asserts
  `PCR_SERVICE_CA_BUNDLE_PATH` on deploy. It is set on the STE app today, but a new environment would come
  up without it and fail every relay at the TLS handshake. This must move to Terraform (§5) before 1.4
  provisions anything — it is the most likely way a new environment silently breaks.
