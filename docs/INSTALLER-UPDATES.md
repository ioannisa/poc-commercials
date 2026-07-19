# Desktop auto-update & installer distribution

The desktop app checks the server for a newer published version at every
startup and walks the user through downloading and launching the installer.
The whole flow was designed so that **publishing a release never requires a
server restart or a `server.yaml` edit** — the advertisement is runtime data
in the central schema, edited by the super admin.

Implemented and E2E-verified on macOS on 2026-07-19 (publish → dialog over
the login screen → 40 MB download with progress → DMG mounted → app exits).

## The three version concepts (do not conflate them)

| Concept | Where it lives | Who changes it |
|---|---|---|
| **App version** — what this desktop build IS | `libs.versions.toml` `app` → jpackage `packageVersion` **and** a generated resource read at runtime (`update/AppBuildInfo.kt`) | developer, per release |
| **Server version** — the server's own build stamp | `libs.versions.toml` `server` → generated resources: `config/ServerBuildInfo.kt` (reported by `/version` + OpenAPI doc) AND `mcp/CommercialsMcpServer.SERVER_VERSION` (the MCP protocol handshake) AND the backend module jar coordinates | developer |
| **Published advertisement** — what clients are OFFERED | `app_settings` rows in the central schema (`app.latestVersion`, `app.minSupportedVersion`, `app.installer.{dmg,msi,deb}`) | super admin, at runtime |

Both semantic versions are the ONLY version literals in the repo — every build
script and every baked runtime resource reads `libs.versions.{app,server}`.
(The lone remaining `1.0.0` in `settings.gradle.kts` is the third-party
foojay-resolver plugin's own version, not ours.) Kept as two because the
desktop client and server release independently; collapse to one alias if you
ever want lockstep.

An earlier idea — a `/reload` endpoint that re-reads `server.yaml` live — was
deliberately dropped: `server.yaml` is bootstrap config (ports, route
mounting, DB pools freeze at startup in Ktor), while "what's the latest
version" is operational state. Operational state belongs in the database.

## Server surface

- **`GET /version`** (OPEN, next to `/health`): the advertisement + the
  server's own version. Open on purpose — a client below `minSupported` must
  learn that, and fetch its installer, BEFORE anyone can log in.
- **`GET/PUT /api/admin/app-update`** (super admin): read/patch the
  advertisement. PUT semantics: `null` = leave untouched, blank = clear.
- **`/downloads`** (OPEN static mount): serves the installer files from the
  directory named by `server.yaml` `downloads:`. Excluded from gzip
  (installers are already-compressed containers).

Installer URLs may be **relative** (`/downloads/X.msi`): each client resolves
them against its own `server.baseUrl`, so the same rows work on localhost,
the tunnel hostname, and any future domain. Spaces in pasted filenames are
%-encoded client-side (jpackage names artifacts "Commercials Manager 2-…").

## Client behaviour (`desktopApp/…/update/`)

- Startup check is **best-effort and off the critical path**: server down,
  old server without `/version`, junk response → silently no update, the app
  never waits.
- `current < latest` → dialog over the login screen (localized, all six
  languages): *Install now / Later*.
- `current < minSupported` → **mandatory**: not dismissable, only
  *Download & install* or *Exit*.
- Download to the OS temp dir with progress, then hand-off + self-exit:
  - **Windows**: `msiexec /i` — the `upgradeUuid` in the package makes it an
    in-place upgrade (app exits first: MSI cannot replace locked files).
  - **macOS**: `open` mounts the DMG; the user drags to Applications
    (jpackage DMGs carry no auto-installer — inherent).
  - **Linux**: `xdg-open` routes the .deb to the distro installer.
- Version compare is numeric per segment (`1.10.0 > 1.9.3`).

## What survives an upgrade

- **All KSafe-stored state survives** (language, theme, font size, window
  geometry, remember-me/biometric gate): the data file lives under
  `~/.eu_anifantakis_ksafe/` in the USER profile and the AES key in the OS
  store (Keychain/DPAPI) — a DMG replace or MSI upgrade only touches the
  installation directory. `appNamespace` is pinned
  (`eu.anifantakis.commercials`) so keys/data line up across versions, and
  `includeAllModules = true` keeps `jdk.unsupported` in release builds
  (OS-backed key custody, not the SOFTWARE-tier fallback).
- **`config.properties` survives via the per-user config dir**: the JVM
  client resolves explicit path → working dir → per-user dir
  (`%APPDATA%\CommercialsManager` / `~/Library/Application Support/CommercialsManager`
  / `$XDG_CONFIG_HOME/CommercialsManager`), and every successful working-dir
  load is mirrored into the per-user dir — so even if an MSI upgrade sweeps
  a config placed next to the launcher, the next start falls through to the
  per-user copy.

## Publishing a release (the whole ceremony)

1. Bump `appVersion` in `gradle.properties`; build the installers locally
   (`:desktopApp:packageDmg` / `packageMsi` / `packageDeb`, each on its OS).
2. Copy them into the server's `downloads:` directory
   (tip: rename without spaces, e.g. `CommercialsManager2-1.2.0.msi`).
3. As super admin, either use **Preferences → Maintenance → Application
   versions** (in-app dialog), or:
   ```bash
   curl -X PUT https://<server>/api/admin/app-update \
     -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
     -d '{"latest":"1.2.0","msi":"/downloads/CommercialsManager2-1.2.0.msi", ...}'
   ```
   No restart. Every client sees it at its next startup.

## Why installers are NOT on GitHub

The repo's CI workflow (`build-installers.yml`) uploads workflow *artifacts*,
which need a GitHub login and expire — unusable as update URLs. GitHub
*Releases* would work today, but the repo may be made private later, which
would break every installed client's download URL. Self-hosting under
`/downloads` keeps distribution independent of repo visibility. (CI can't
build installers right now anyway — the ksafe 2.3.0-SNAPSHOT dependency
exists only locally; deferred by owner.)

## Done

- [x] Version plumbing (`appVersion`/`serverVersion` → runtime-readable)
- [x] `app_settings`-backed advertisement + `GET /version` + admin GET/PUT
- [x] `/downloads` static mount (`server.yaml downloads:`), gzip-excluded
- [x] Desktop updater: check, optional/mandatory dialog (6 languages),
      download with progress, per-OS install hand-off, self-exit
- [x] Space-in-URL hardening (%-encoding), unit-tested
- [x] Super-admin **Application versions** dialog in Preferences → Maintenance
- [x] E2E verified on macOS (optional-update path); admin dialog exercised live
- [x] Upgrade-proof `config.properties`: per-user dir resolution + write-through
      mirror (env/filesystem-probed, no `os.name` read)

## Pending / known gaps

- [ ] **Windows & Linux untested end-to-end** — the msiexec / xdg-open paths
      compile and are reasoned, but no real run yet (Windows is the
      project's generally unverified half).
- [ ] **Mandatory-update path untested live** (`minSupported` was never set
      during the E2E run; the dialog logic is unit-tested only).
- [ ] **No installer integrity check** — the client trusts whatever
      `/downloads` serves over the TLS of the deployment. Future hardening:
      `app.installer.sha256.*` settings + client-side digest verification.
- [ ] **No code signing / notarization** — unsigned DMG/MSI trigger
      Gatekeeper / SmartScreen friction on machines that didn't install the
      first version manually.
- [ ] **Shell dialog theming** — the update dialog follows the OS light/dark,
      not the in-app theme preference (it renders outside `App()`; accepted
      divergence for one transient dialog).
- [ ] **macOS is drag-to-install**, not silent: jpackage DMGs have no
      auto-installer. A truly silent update path would mean Conveyor (or a
      custom .pkg) — revisit only if the manual step annoys real users.
- [ ] **CI cannot produce installers** until the ksafe SNAPSHOT situation is
      resolved; releases are built locally.
- [ ] Release-notes surface (the advertisement carries versions and URLs,
      not human-readable notes; a `releaseNotesUrl` setting would be cheap).
