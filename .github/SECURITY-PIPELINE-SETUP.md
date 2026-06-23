# Automated Security Pipeline — Setup

This repo ships an automated security pipeline:

| File | Role |
| --- | --- |
| [`.github/dependabot.yml`](dependabot.yml) | Opens dependency-bump PRs (Gradle graph + GitHub Actions). |
| [`.github/workflows/codeql.yml`](workflows/codeql.yml) | Weekly + per-PR static security analysis (`build-mode: none`). |
| [`.coderabbit.yaml`](../.coderabbit.yaml) | Security-tuned CodeRabbit review (the GitHub App reads this). |
| [`.github/workflows/pinned-artifact-freshness.yml`](workflows/pinned-artifact-freshness.yml) | Weekly check of URL-pinned artifacts (mosh4j, bundled BC/protobuf, Node, Monaco, SithTermFX) → opens a tracking issue. |
| [`.github/workflows/auto-security-release.yml`](workflows/auto-security-release.yml) | On a merged `security`-labeled PR, cuts the next `vX.Y.PATCH` release → triggers the existing `build-release.yml`. |

**Flow:** Dependabot/CodeQL/Freshness *detect* → CodeRabbit *reviews the PR* → **a human merges** (the gate) → `auto-security-release` *cuts the tag/release* → `build-release.yml` *builds + signs + uploads binaries*.

The workflow files alone are not enough. Complete these one-time steps on github.com.

## 1. Repo labels
Create these labels (Issues → Labels), or the workflows that apply them will fall back to creating them at runtime:
- `security` — Dependabot security PRs and any manual security fix PR must carry this; it is what triggers `auto-security-release`.
- `pinned-dependency` — used by the freshness tracking issue.

## 2. Dependabot toggles (Settings → Code security)
A `dependabot.yml` only enables *version* updates. Turn ON, separately:
- **Dependency graph**
- **Dependabot alerts**
- **Dependabot security updates** (auto-opens fix PRs for CVEs, labeled `security`)

## 3. Release token (so the auto-release can trigger `build-release.yml`)
> A release created with the default `GITHUB_TOKEN` does **not** trigger other workflows. The auto-release therefore needs its own token.

Choose ONE:

**A. GitHub App (recommended).** Create an App (org or personal), grant **Repository contents: Read & write**, install it on `chardonnay/korTTY`, then add repo secrets:
- `RELEASE_APP_ID` = the App's ID
- `RELEASE_APP_PRIVATE_KEY` = the App's generated `.pem` (full contents)

**B. Fine-grained PAT (simpler).** Create a PAT with **Contents: Read and write** on this repo, store as secret `RELEASE_PAT`, then edit `auto-security-release.yml`: delete the `Mint GitHub App token` step and replace every `${{ steps.app-token.outputs.token }}` with `${{ secrets.RELEASE_PAT }}`.

## 4. Branch protection on `main` (Settings → Branches / Rulesets)
- Require a pull request before merging; **require ≥1 human approval** (CodeRabbit's approval must NOT satisfy the gate on its own).
- Require status checks to pass: **CodeQL** + the CodeRabbit review check + existing build checks.
- Dismiss stale approvals on new commits; require branch up to date; no force-push; enforce for admins.
- If protection blocks the optional version bump-back push, either grant the App/PAT a push bypass, or set input `bump_gradle=false` (the bump step is already `continue-on-error`).

## 5. CodeRabbit
The GitHub App is already installed. Keep its repo scope minimal (no admin). `.coderabbit.yaml` deliberately does **not** ignore `dependabot[bot]`, so Dependabot's security PRs get reviewed.

## Versioning behavior
`auto-security-release` derives the version **line** from the newer of {`build.gradle.kts` version, highest `vX.Y.Z` tag} and increments the patch. So:
- **Current baseline:** `build.gradle.kts` is `2.2.1` and the highest tag is `v2.2.0`. Once you publish the `v2.2.1` release yourself, the first automated security release is **`v2.2.2`**, then `v2.2.3`, … (each driven by a merged `security`-labeled PR).
- **Moving to a new minor/major (e.g. 3.x):** publish `v3.0.0` (and set `version` in `build.gradle.kts` to `3.0.0`, which you do anyway) → the next automated release is `v3.0.1`. The old line is dropped automatically; **no workflow edit needed.** Scanning always targets `main`, so old releases are never re-scanned.

## Honest scope of "auto-fix"
- **Fully automatic fix PRs:** only Dependabot (Gradle-graph + Actions deps).
- **Tracking issue (human/agent then fixes):** the freshness job, for URL-pinned artifacts (a bump needs a new SHA-256 pin + per-arch JAR refresh — not safely auto-editable).
- **Review only:** CodeQL and CodeRabbit *detect/review*; turning a code finding into a commit needs a human or a coding agent. CodeRabbit does not open whole-repo fix PRs by itself.
- Optional later (Phase 3): a scheduled CodeRabbit-CLI agent run that authors fix PRs for CodeQL/Gitleaks findings — still routed through the same review + human-merge gate.
