# Second, Independent GitHub Pages Deployment (Domain Move + Fallback)

## Context

The korTTY website + guide were previously published via [`.github/workflows/docs-site.yml`](../.github/workflows/docs-site.yml) to GitHub Pages on `chardonnay/korTTY`, reachable at the custom domain `kortty.app`. That domain binding was **not a CNAME file in the repo** (none existed, neither in the root nor on any branch), but a purely GitHub-side Pages setting (confirmed via `gh api repos/chardonnay/korTTY/pages` → `"cname": "kortty.app"`, `"build_type": "workflow"`).

**GitHub Pages allows only a single Pages site per repository** — a second branch in the same repo therefore cannot produce a second, independent `*.github.io` instance. A second, separate repository is required.

**Final role split (as decided by the user):**
- **`chardonnay/korTTY_website`** (new, created by the user) now carries the custom domain `kortty.app` — this is the "official", branded instance.
- **`chardonnay/korTTY`** (this repo) loses the custom domain — the user removed it in the Pages settings so the GitHub default is active again. As a result, the existing, **unmodified** Pages deployment of `korTTY` now automatically serves as an independent fallback instance at `https://chardonnay.github.io/korTTY/` — with no redirect, since no custom domain is configured there anymore.

This split required **no change** to the existing `deploy` job: it stays exactly as it was and, once the custom domain was removed (a user action, not part of this implementation), automatically acts as the fallback. The only implementation work was a **new, additional job** that, on every `docs-site.yml` run, additionally pushes the same `build/site` content to `korTTY_website` — including a `CNAME` file containing `kortty.app`, since that target repo (unlike `korTTY`) has to use branch-based Pages (cross-repo publishing via `actions/deploy-pages` isn't possible — that action can only run inside the target repo itself, using that repo's own OIDC context). Branch-based Pages with a custom domain needs a `CNAME` file present on the branch, otherwise GitHub disables the domain again on the next deploy — so our push job has to write it on every run, since the branch is fully replaced by a force-push each time.

**Robustness (user requirement):** the new push job must not turn the whole workflow run red if `korTTY_website` no longer exists, was renamed, or the PAT is invalid — instead it should surface a clearly visible **warning**, while the rest of the run (in particular the unmodified fallback deploy of `korTTY` itself) completes green as normal.

## Setup: `chardonnay/korTTY_website`

The repo already existed (created by the user). Steps taken before merging the workflow change:

1. Create a fine-grained PAT: https://github.com/settings/personal-access-tokens/new — Resource owner `chardonnay`, Repository access: **only** `korTTY_website`, Permission **Contents: Read and write**, nothing else. Set a dated expiration.
2. Store the token as a secret in `chardonnay/korTTY` → Settings → Secrets and variables → Actions, named **`KORTTY_WEBSITE_TOKEN`** — before merging the workflow change, otherwise the first run fails on the missing secret (though thanks to the robustness logic, that only shows as a warning, not a red run).
3. Don't configure `korTTY_website`'s Pages settings yet (the "Deploy from a branch" dropdown has nothing to select before the first push, while the repo has no `gh-pages` branch).
4. After the first successful workflow run (creates `gh-pages` with content + `CNAME` file): `korTTY_website` → Settings → Pages → Source: **Deploy from a branch** → Branch `gh-pages` / `(root)` → Save. Then, on the same screen, add/verify the **Custom domain** `kortty.app` (DNS records must still point at GitHub Pages — technically unchanged, only which repo "claims" the domain changes).
5. Only **after** that does the user remove the custom domain from `chardonnay/korTTY`'s Pages settings (their own action, not part of this implementation) — GitHub only ever lets one repo claim a given domain at a time, hence this order: verify the new domain where it should live first, then release it at the old location, to avoid a gap with no working `kortty.app`.

## Workflow change: `.github/workflows/docs-site.yml`

Two additions; no existing line (in particular not the `deploy` job) was otherwise changed.

**(a) Additional step at the end of `build`** (after the existing `actions/upload-pages-artifact@v5` step, reading the same `build/site` path so the fallback and website-repo content are guaranteed to be identical):

```yaml
      - name: Upload site content for the korTTY_website publish job
        uses: actions/upload-artifact@v7
        with:
          name: docs-site-artifact
          path: build/site
          include-hidden-files: true # keep .nojekyll and other dotfiles
          retention-days: 1
```

Reason for the separate upload: `actions/upload-pages-artifact` produces a special Pages-only format that only `actions/deploy-pages` can read — the new job needs an ordinary, downloadable artifact.

**(b) New `publish-website-repo` job, a sibling of `deploy`** (both depend only on `build`, not on each other — a failure here must never block `korTTY`'s fallback deploy, and vice versa):

```yaml
  publish-website-repo:
    name: Publish to korTTY_website (kortty.app)
    needs: build
    runs-on: ubuntu-latest
    permissions:
      contents: read
    concurrency:
      group: korTTY-website-publish
      cancel-in-progress: false
    steps:
      - name: Download site content
        uses: actions/download-artifact@v8
        with:
          name: docs-site-artifact
          path: site

      - name: Add Pages custom-domain + Jekyll-bypass markers
        run: |
          echo "kortty.app" > site/CNAME
          touch site/.nojekyll

      - name: Push to korTTY_website's gh-pages branch
        env:
          KORTTY_WEBSITE_TOKEN: ${{ secrets.KORTTY_WEBSITE_TOKEN }}
        run: |
          set -uo pipefail
          cd site
          git init -q -b gh-pages
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add -A
          git commit -q -m "Publish korTTY website: ${GITHUB_SHA}"

          # Non-fatal by design: if korTTY_website has been deleted, renamed,
          # or the PAT has expired/lost access, this must not fail the run or
          # block the sibling `deploy` job (korTTY's own fallback Pages) — it
          # should just surface a visible warning and move on.
          if git -c http.https://github.com/.extraheader="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$KORTTY_WEBSITE_TOKEN" | base64 -w0)" \
               push --force https://github.com/chardonnay/korTTY_website.git HEAD:gh-pages; then
            echo "Publish to korTTY_website succeeded."
          else
            echo "::warning title=korTTY_website publish failed::Could not push to chardonnay/korTTY_website (gh-pages). The repo may have been deleted/renamed, or KORTTY_WEBSITE_TOKEN may be missing/expired/lacking access. kortty.app was NOT updated this run — the fallback deployment at chardonnay.github.io/korTTY is unaffected."
          fi
```

Details:
- **Robust rather than hard-failing**: the `git push` doesn't run under `set -e`, and its result is checked explicitly — on failure the step still exits with code 0 and the job stays green, instead of marking the run red. The failure is surfaced via a `::warning::` annotation (a yellow warning icon in the run summary and Checks tab) without blocking the workflow. Even a permanently broken/deleted `korTTY_website` lets future `docs-site.yml` runs keep completing green, each with a clearly visible warning instead of a red job — the unmodified `deploy` job (fallback) is fully independent of this.
- **`CNAME` file only here, not in `korTTY`'s own deploy**: `korTTY`'s `deploy` job stays completely untouched and domain-agnostic (Actions-based deploy needs no CNAME file anyway). The `CNAME` file containing `kortty.app` is written only into the content pushed to `korTTY_website`, and rewritten on every run (since the branch is fully replaced by a force-push each time) — otherwise GitHub would disable the custom domain there again after the next automatic re-deploy.
- **No third-party action** (e.g. `peaceiris/actions-gh-pages`): the repo currently uses only official `actions/*` actions (see the precedent in [`ai-catalog-release.yml`](../.github/workflows/ai-catalog-release.yml), which likewise pushes to a dedicated external repo via a PAT secret). The ~10 lines of plain git shell are easier to audit and need no new SHA pin.
- **Orphan branch + force-push on every run**: `git init -q -b gh-pages` inside the downloaded artifact directory, a single commit containing the full site tree (including `CNAME`), `push --force` unconditionally overwrites — this behaves identically for "branch doesn't exist yet" (first run) and every subsequent run.
- **Concurrency group `korTTY-website-publish`, not `pages`**: the existing workflow-level group `group: pages` holds a slot for the whole run (build+deploy+publish-website-repo) until every job finishes — a job-level `concurrency: group: pages` here would wait on itself (deadlock). A dedicated group name avoids that.

Resulting job structure:
```
        ┌── deploy               (needs: build)   → korTTY's own Pages, after domain removal: chardonnay.github.io/korTTY (fallback, UNCHANGED)
build ──┤
        └── publish-website-repo (needs: build)   → korTTY_website (gh-pages) → kortty.app (new primary, branded instance)
```

## Verification (performed)

1. PAT + secret setup as above.
2. Committed the workflow change, manually triggered `docs-site.yml` via `workflow_dispatch` and watched the run in the Actions UI: `build` → `deploy` and `publish-website-repo` ran in parallel, both green.
3. After the first green run: `git ls-remote https://github.com/chardonnay/korTTY_website.git` showed `refs/heads/gh-pages`; content included `CNAME` with `kortty.app`.
4. Configured Pages settings in `korTTY_website` (branch + custom domain) via the API — the repo had defaulted to `build_type: workflow` with no deploy workflow of its own, which was serving 404s; switched it to `build_type: legacy` sourcing from `gh-pages`/root, then triggered an initial build. `kortty.app` now serves the site and `/guide/`.
5. `korTTY`'s custom domain was removed (user action); confirmed `https://chardonnay.github.io/korTTY/` serves the same website with no redirect, and `gh api repos/chardonnay/korTTY/pages` shows `"cname": null`.
6. Robustness was verified live: before the secret existed, `publish-website-repo` completed green with a `::warning::` annotation ("Could not push to chardonnay/korTTY_website...") while `deploy`/the fallback ran unaffected. After adding the secret, a re-run published successfully with no warning.
7. Confirmed both deployments serve equivalent content (site + `/guide/`) at their respective URLs.
