## Meeting Minder Release Checklist

This mirrors the episode6 app repos' release process (see headache-tracker and
podcast-hacker): we deploy a signed APK to a GitHub release via CI
(`build-installers.yml`) instead of publishing to sonatype. Agent skills in
[.agents/](./.agents) automate most of it (`release-branch-skill`,
`ship-release-skill`, `update-docs-skill`).

### Versioning

- `name` in `self.versions.toml` is the single source of truth: `MAJOR.MINOR.PATCH`.
  **Cutting a release branch bumps the patch by 10**, so regular releases land on
  multiples of 10 and the 9 values in between are reserved for hotfixing that release
  (`1.2.30` → hotfixes `1.2.31`–`1.2.39`, next release `1.2.40`). This keeps hotfixes
  visible in the name (distinct tags and artifact filenames) at the cost of one patch
  digit — 1000 releases per minor, 9 hotfixes per release.
- The android versionCode is **derived** from the name — never set it manually.
  Formula (mixed radix): `(MAJOR × 256 + MINOR) × 10000 + PATCH`, e.g. `1.2.30` →
  `2580030`, hotfix `1.2.31` → `2580031`, next release `1.2.40` → `2580040`. Newer
  versions always produce bigger codes, so older builds can never override newer ones.
  (The code isn't eyeball-decodable back into major/minor — the last 4 digits are the
  patch, the rest is `major × 256 + minor`.)
- Limits: major and minor max out at 255, patch at 9999, and major must stay >= 1
  (limits inherited from the episode6 app-repo schema so versionCodes stay consistent
  across repos). The highest possible code — `255.255.9999` → 655,359,999 — sits well
  under Google Play's 2,100,000,000 versionCode cap.
- The formula lives in the root `build.gradle.kts` — the single source of truth.
  Release tooling queries it via `./gradlew -q printReleaseVersionCode` instead of
  reimplementing it.
- **No `-SNAPSHOT` suffixes** in the version name. Snapshot-ness is instead determined
  at build time: every build is a snapshot except CI builds off a release tag
  (`GITHUB_REF=refs/tags/v*`). `main` always carries the *next* release's version, so
  the release branch inherits the correct version when cut.
- Snapshot builds derive their versionCode from git instead of the formula: the commit
  count at HEAD's merge-base with main. Snapshots install under their own
  applicationId, so their codes never compete with release codes; builds from main
  carry a strictly growing code (a newer main snapshot always installs over an older
  one), while branch/PR builds are locked to their closest main ancestor's code so a
  later main build can install right over them. This needs full git history — every
  gradle-running CI checkout sets `fetch-depth: 0`, and the build rejects shallow
  clones rather than silently under-counting.
- Version sync is CI-enforced: `verify-versions.yml` runs `scripts/verify-versions.sh`
  on every PR and main push, failing if `CHANGELOG.md` lacks a `### v<VERSION>` section
  for the current version. The android versionName/versionCode need no check — they
  read the toml (or git) at build time and can't drift.
- **Snapshot builds carry their own app identity** so they install side-by-side with
  release builds instead of overwriting them: the display name gains a ` (SNAPSHOT)`
  suffix and the android applicationId becomes
  `com.episode6.meetingminder.snapshot` (see `selfAppName` / `selfAppId` in the
  root `build.gradle.kts`; the `namespace` stays fixed at
  `com.episode6.meetingminder`, so R and manifest class references are unaffected).
  Snapshot builds also swap in their own app icon (dark charcoal background; releases
  keep the episode6-orange one) via `manifestPlaceholders` + the `*_snapshot` mipmaps. Debug
  builds go one step further and append a `.debug` applicationIdSuffix (see
  `app/build.gradle.kts`), so a local `installDebug` coexists with an installed
  CI-built snapshot APK instead of clobbering it (or being blocked by its signature).

### Cut new Release Branch

1. Ensure main branch is green
2. `<VERSION>` = the current `name` in `self.versions.toml`
3. `git checkout -b release/v<VERSION>`
4. Push/track empty branch

### Version bump PRs

- Create 2 PRs
    - `[VERSION] Snapshot v<NEXT_VERSION>` points at `main`
        - Bump `name` in `self.versions.toml` (VITAL). Bump the **patch by 10** (e.g.
          `1.2.30` → `1.2.40`); major/minor bumps are an explicit human decision (and
          reset the lower segments to 0). Never hand out the 9 values between release
          patches — they're reserved for hotfixing the release below them. The
          versionCode derives automatically.
        - Update `CHANGELOG.md`: add a new `### v<NEXT_VERSION> - Unreleased` section and
          stamp the outgoing `v<VERSION>` section with its release date
    - `[VERSION] Release v<VERSION>` points at new release branch
        - Stamp the release date on the `v<VERSION>` section of `CHANGELOG.md` and ensure
          all changes since the last release are documented
        - Verify `name` in `self.versions.toml` is already correct (no version change
          expected — main carried the right version at cut time)

### Harden Release Branch

- Sanity pass on a device/emulator: swipe through a few days, select and unselect
  meetings, set alarms and confirm one actually rings full-screen over the lock screen,
  share the schedule, then move an event in Google Calendar and confirm the
  "changed since you shared" notification arrives.
- Fix any bugs on the `main` branch first then cherry-pick (via PR) into release branch

### Release

1. From the release branch: `./scripts/ship-release.py --output /tmp/release-result.json`
   — creates the GitHub release + tag `v<VERSION>` pointing at the release branch, with
   notes extracted from `CHANGELOG.md`
2. The tag push triggers `build-installers.yml`, which builds a signed release APK and
   attaches it to the release
3. Verify the release: the APK is attached and carries the right version; sideload it
   (`adb install`).

### Hotfixes

- We do not cut new release branches for hotfixes, instead we append to the affected
  release branch and add a new release tag
- All fixes (including hotfixes) should be applied to the `main` branch first whenever
  possible and cherry-picked onto the appropriate release-branch for a hotfix
- A hotfix needs its own version bump PR on the release branch: bump the patch by 1
  within the release's reserved range (e.g. `1.2.30` → `1.2.31`, up to `1.2.39` — 9
  hotfixes per release) and update `CHANGELOG.md`. No coordination with `main` is
  needed — the derived versionCodes keep ordering (main's `1.2.40` always outranks any
  `1.2.3x` hotfix)
