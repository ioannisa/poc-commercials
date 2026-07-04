# Commercials Manager (POC)

Kotlin/Compose Multiplatform re-implementation of a legacy Windows TV/radio
ad-scheduling application. Targets: **Android, iOS, Desktop (JVM), Web (JS +
Wasm)**, plus a **Ktor server** in front of per-station MySQL schemas.

## Architecture

Feature-first Clean Architecture per the `kmp-developer` skill: every feature
is three Gradle modules (`presentation → domain ← data`), dependencies are
unidirectional and Gradle-enforced, features **never** depend on each other —
anything shared by two features lives in `:core:*`. Screens follow MVI
(`State` / `Intent` / `Effect`) with a public `<Name>ScreenRoot` and a private
`<Name>Screen`; **every screen has its own ViewModel** — genuinely shared
state gets a narrow store instead (e.g. `ScheduleCellsStore` between the grid
and the break-detail console).

```
build-logic/                    convention plugins (kmp.library / kmp.domain / kmp.feature)
core/
  domain                        DataResult, DataError, RemoteError, AppRole, party search contract
  data                          AuthSession, authenticated Ktor client, KSafe, AppConfig, party search impl
  presentation                  MVI helpers, global state, Navigator, ApplicationScaffold, theme, file pickers
    grids                       nested pure-UI toolkit: the scheduler grid (LazySchedulerGrid, keyboard nav)
feature/
  auth/                         login, change-password + recovery-codes dialogs
  timetable/                    scheduler grid + Εύρεση finder + break-detail console
  schedule-email/               ΠΡΟΓΡΑΜΜΑΤΙΣΜΟΙ: activity drill-down, preview webview, send
  preferences/                  theme + entry hub (KSafe-backed UserPreferences)
  user-management/              super admin: accounts, grants, password resets
  migration-console/            super admin: legacy mysqldump → hosted station
  databases/                    super admin: hosted schemas, safe/hard delete
shared/                         the app layer: App, NavigationRoot, Koin assembly (iOS framework "Shared")
androidApp/ desktopApp/ webApp/ iosApp/   entry points
reports-client/                 client printing service — cross-layer (UI + network + engine), so root, not :core
server/ persistence/ migration/ mailer/   backend (Ktor + MySQL + legacy import + SMTP) — outside the client tree
reportcore/                     shared client+server report engine (jvm renders on server, js/wasm in browser)
```

Navigation is Navigation3: each feature declares `@Serializable <Feature>NavType`
routes and an `<feature>Entries(...)` provider in its `Navigation<Feature>.kt`;
`shared/navigation/NavigationRoot.kt` assembles them around a `Navigator` and
wires cross-feature transitions as callbacks. DI is classic Koin DSL, one
module file per feature under `shared/.../di/` assembled by `initKoin`, guarded
by `KoinGraphTest`.

## Running

```shell
./gradlew :server:run                    # Ktor server (server.yaml + MySQL)
./gradlew :desktopApp:run                # Desktop client
./gradlew :androidApp:assembleDebug      # Android APK
./gradlew :webApp:wasmJsBrowserDistribution   # Web bundle
```

iOS: open `/iosApp` in Xcode and run (the `Shared` framework comes from `:shared`).

## Conventions worth knowing

- All remote calls return `DataResult<T, E>`; server error messages
  (`{"error": ...}`) survive to the operator via `RemoteError.Server` /
  feature-specific error types. `CancellationException` always rethrows.
- Programme colours in the scheduler are **data** (operator-assigned in the
  legacy app), never theme-adapted; text contrast is computed by luminance.
- Mono-lingual Greek POC: UI strings are plain `String` (recorded deviation
  from the skill's `StringKey` localization).
- The Koin compiler plugin is intentionally NOT used (cross-module
  definitions); `KoinGraphTest` guards the graph instead.
