---
name: release-branch-skill
description: >-
  Cut a new release branch and prepare the version-bump PRs, as defined in
  RELEASE_CHECKLIST.md. Use whenever the user asks to "cut a release branch"
  (or to cut/create/start a new release branch): verifies main is green,
  creates release/v<VERSION>, and opens the snapshot-on-main and
  release-on-branch version-bump PRs that update self.versions.toml (the
  versionCode derives from the version name automatically) and CHANGELOG.md.
---

# Cut Release Branch Skill

This skill automates and describes the process of cutting a new release branch and preparing the version bumps, as defined in `RELEASE_CHECKLIST.md`.

**No `-SNAPSHOT` versions in this repo** (unlike the episode6 library repos): APKs build on every push to `main`, so `main` always carries the *next* release's plain numeric version. The release branch therefore inherits the correct version when cut — the "release" PR only finalizes the changelog.

**`-SNAPSHOT` *dependencies* are a separate concern**: it's fine for a PR to point a dependency in `gradle/libs.versions.toml` at a `-SNAPSHOT` library version while testing unreleased library changes. The `no-snapshot-deps.yml` CI workflow fails any PR whose `libs.versions.toml` still contains `-SNAPSHOT` — the guard exists to keep snapshot dependencies from *merging*, not to forbid them during development. Swap in the released version before merge (and certainly before cutting a release branch).

## Steps to Execute

### 1. Pre-check
- Ensure the `main` branch is passing all CI checks (is "green").
- `<VERSION>` = the current `name` in `self.versions.toml` on `main`. This is the version being released.

### 2. Cut new Release Branch
- Checkout the `main` branch and pull the latest changes.
- Create a new branch: `git checkout -b release/v<VERSION>`
- Push the empty branch and set it to be tracked: `git push -u origin release/v<VERSION>`

### 3. Version Bump PRs
Create two separate Pull Requests.

#### PR 1: Next version on `main`
- **Target Branch:** `main`
- **PR Title:** `[VERSION] Snapshot v<NEXT_VERSION>`
- **Changes:**
    - **(VITAL)** Update `name` in `self.versions.toml`. When computing `<NEXT_VERSION>`, increment **the patch version by 10** (e.g., `1.2.30` → `1.2.40`). Never automatically increment the major or minor version — those bumps require explicit human decision — and never hand out the 9 patch values between releases: they're reserved for hotfixing the release below them. The android versionCode derives from the name automatically (see the Versioning section of `RELEASE_CHECKLIST.md`); never set it manually.
    - **(VITAL)** Update `CHANGELOG.md` to include a new `### v<NEXT_VERSION> - Unreleased` section, AND update the version being released with its release date.

#### PR 2: Release Finalization on Release Branch
- **Target Branch:** `release/v<VERSION>`
- **PR Title:** `[VERSION] Release v<VERSION>`
- **Changes:**
    - **(VITAL)** Update `CHANGELOG.md` with the release date on the `v<VERSION>` section. Ensure all changes since the last release are documented.
    - Verify `name` in `self.versions.toml` is already correct (no version change expected — main carried the right version at cut time).

### 4. Create Pull Requests
- Use `gh pr create` (as drafts, per repo convention) or the GitHub UI to create the Pull Requests for the version bump branches created in step 3.

## Verification
- After these steps, the project is ready for the "Harden Release Branch" phase (sanity pass on the app), which requires manual verification and cherry-picking of bug fixes. See `RELEASE_CHECKLIST.md`.
