---
name: ship-release-skill
description: >-
  Ship a release branch by publishing a GitHub release using the gh CLI. Parses
  the version name from self.versions.toml (the versionCode derives from it),
  gets release notes from CHANGELOG.md, and relies on the tag push triggering
  build-installers.yml to attach the signed APK to the release.
---

# Ship Release Branch Skill

## Overview
This skill guides the agent in shipping a release branch by creating and publishing a GitHub release pointing to the tip of the release branch. It ensures consistency by:
1. Resolving the release version directly from `self.versions.toml` on the target release branch (never assuming the branch name matches the version name, as hotfixes are appended to existing release branches by bumping the patch within the release's reserved range, e.g. `1.2.30` → `1.2.31`). The release versionCode is derived from the name by gradle (`./gradlew -q printReleaseVersionCode`) — see the Versioning section of `RELEASE_CHECKLIST.md` for the formula.
2. Extracting release notes from the corresponding section in `CHANGELOG.md`.
3. Publishing the GitHub release using the `gh` CLI with matching tag name and release name (format: `v<VERSION>`).
4. The tag push triggers `.github/workflows/build-installers.yml`, which builds a signed release APK and attaches it to the release created in step 3.

## Dependencies
- `release-branch-skill`: Typically run after a release branch is cut and hardened.

## Prerequisites
- **Merge PRs via GitHub**: Ensure the version bump PR (and any hardening PRs) are merged via the GitHub UI or `gh pr merge`. **NEVER** use local merge commits on the release branch.
- **Pull Latest**: Once PRs are merged on GitHub, checkout the release branch locally and pull the latest changes from `origin` to ensure the local branch is up-to-date before starting the release process.
- **Verify Versions**: `CHANGELOG.md` must contain a `### v<VERSION>` section for the `name` in `self.versions.toml` (`./scripts/verify-versions.sh` checks this; the script refuses to ship from a non-`release/*` branch).

## Quick Start
To perform a dry-run and verify release notes/version before shipping:
```bash
./scripts/ship-release.py --dry-run --output /tmp/release-dry-run.json
```

To ship the current release branch:
```bash
./scripts/ship-release.py --output /tmp/release-result.json
```

## Utility Scripts
The skill uses the `./scripts/ship-release.py` script.

### Arguments
- `--branch <branch>`: Target branch/ref to point the release to (defaults to current branch). Must be a `release/*` branch (warning only on dry-run).
- `--dry-run`: Prints release details and the `gh` command without executing them.
- `--output <file_path>`: (Required) Path to write a JSON report of the release results.

### Example JSON output (`/tmp/release-result.json`):
```json
{
  "success": true,
  "dry_run": false,
  "tag": "v1.0.0",
  "title": "v1.0.0",
  "version_code": "2560000",
  "branch": "release/v1.0.0",
  "url": "https://github.com/episode6/meeting-minder/releases/tag/v1.0.0",
  "notes": "- Initial release: pick today's meetings, get loud alarms, share your busy times..."
}
```

## After Shipping
- Watch the `build-installers.yml` run triggered by the new tag; when it finishes, verify the signed APK is attached to the release and carries the right version.

## Common Mistakes
1. **Shipping from a non-release branch**: `main` always carries a releasable-looking plain version in this repo (no `-SNAPSHOT` markers), so the script refuses to ship unless the target is a `release/*` branch.
2. **Missing Changelog Section**: Forgetting to update `CHANGELOG.md` with the release version. The script will fail if the section matching `v<VERSION>` cannot be found.
3. **Mismatched release notes**: Assuming the release notes can be typed manually. Always extract them directly from `CHANGELOG.md` using the script to avoid discrepancies.
4. **Stale Local Branch**: Forgetting to pull the latest changes from `origin` before shipping, which can lead to releasing an outdated version of the code.
