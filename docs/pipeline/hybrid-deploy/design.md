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

What *is* reusable is the handoff mechanism: `hmcts/trigger-ado-pipeline@v2` and
`hmcts/monitor-ado-pipeline@v1`, authenticated with `HMCTS_CP_ADO_PAT`.

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

Two options. Both are immutable and versioned; they differ in how much plumbing already exists.

| | GitHub Release asset | GitHub Packages (Maven-shaped) |
|---|---|---|
| Publish | `gh release create` / upload asset | `publish` to GitHub Packages |
| ADO pulls with | `GitHubRelease` task or `curl` + PAT | the same `GROUP_ID` / `ARTIFACT_ID` / `ARTIFACT_VERSION` shape pipeline 460 already consumes |
| Estate consistency | new pattern for this estate | matches what CPP ADO tooling already speaks |
| Simplicity | higher | lower (a zip in a Maven coordinate is a slight abuse) |

**Chosen: release asset**, for legibility — a human can see exactly what shipped, and the download needs
no Maven plumbing. Flagging the alternative because if the platform team would rather reuse existing
artefact-download tooling verbatim, publishing to GitHub Packages avoids writing a new download step.

Actions artifacts (`actions/upload-artifact`, what the Build job produces today) are **not** suitable:
they are run-scoped and expire (90 days by default), so there is nothing durable to promote.

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

### 4.1 Prerequisite that will otherwise bite

**`WEBSITE_RUN_FROM_PACKAGE` on `fa-ste-ccp0121-pcrrelay` is currently a blob SAS URL**, set by the
`azureFunctionsDeploy` runs done from a laptop. Any Kudu zip deploy against it — which is what both
`Azure/functions-action` and ADO's `AzureFunctionApp@2` do — fails with a **permanent 409**, not a
transient one (`DEPLOYMENT.md` §7.4).

Before the first automated deploy: set `WEBSITE_RUN_FROM_PACKAGE=1`. Consequence, and it is not optional
— `azureFunctionsDeploy` from a laptop stops being available, because it sets the setting back to a SAS
URL. One mechanism or the other, not both.

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
- Does anything still need `azureFunctionsDeploy`, given §4.1 makes it mutually exclusive with automated
  deploys? Suggest keeping it for local `azureFunctionsRun` only.
