# Project Rules (Android Studio & Kotlin Edition)

## Core

* Understand the existing codebase before making changes.
* Make the smallest change that solves the problem.
* Do not modify unrelated code.
* Reuse existing components, utilities, and patterns.
* Avoid unnecessary abstractions and over-engineering.
* Write clean, readable, idiomatic Kotlin code.
* Preserve existing comments, KDocs, and types in surrounding code.

## Android & Kotlin Architecture

* Use **Kotlin** as the primary language with coroutines and Flow for reactive data streams.
* Centralize all library dependencies in `gradle/libs.versions.toml` (Version Catalog). Do not hardcode version strings inside `build.gradle.kts`.
* Follow modern Android Architecture (UI Layer -> ViewModel / Domain Layer -> Data / Repository Layer).
* For UI: Prefer **Jetpack Compose (Material 3)** or maintain consistency with existing project UI paradigms.
* Use `ViewModel` with `StateFlow` / `SharedFlow` and collect state safely using `collectAsStateWithLifecycle()` or `repeatOnLifecycle`.
* Avoid hardcoded strings and dimensions in UI code; use Android XML resources (`R.string`, `R.dimen`) or Material Theme tokens.

## Persistence & Storage (Room / DataStore)

* Pair database schema updates in Room with explicit migration scripts (`Migration` classes or AutoMigrations).
* Never query Room databases on the Main (UI) thread. Use `@Query` with Coroutine `Flow` or `suspend` functions.
* Never run destructive schema changes or wipe database tables in production without fallback/migration strategy.

## Performance & Memory Management

* **Main Thread Safety**:
  * Never execute synchronous blocking I/O (file access, network requests, heavy audio processing) on `Dispatchers.Main`. Offload to `Dispatchers.IO` or `Dispatchers.Default`.
* **Memory & Lifecycle Safety**:
  * Prevent Context leaks: Never pass Activity context to singletons or static variables; use Application context or weak references where required.
  * Always clean up and unregister `BroadcastReceiver`s, `AccessibilityService` listeners, `AudioRecord` buffers, and handlers in `onDestroy()` / `onCleared()`.
* **UI & List Efficiency**:
  * Use `LazyColumn` / `LazyRow` with explicit `key` parameters for efficient item re-use.
  * Debounce high-frequency events (e.g. search input changes, audio buffer processing, node tree processing).

## Code Quality

* Follow the project's existing conventions and naming patterns.
* Use clear, descriptive names for classes, state properties, and composables.
* Handle errors gracefully (e.g., `Result<T>`, `try-catch`, state error handling) and clean up temporary debug logs before finalizing.
* Never hardcode secrets, API keys, or credentials in source code. Keep sensitive local parameters in `local.properties` or BuildConfig environment variables.

## Refactoring & Legacy Code Cleanup

* **Prune Dead Code Proactively**: Remove unused imports, obsolete helper functions, unused variables, and superseded logic when replacing them with new implementations.
* **Respect Preserved & Legacy Markers**: NEVER delete code or comments explicitly marked with `// DO NOT REMOVE`, `// KEEP`, `// LEGACY`, or `// BACKWARD COMPATIBILITY`.
* **Workspace Verification Before Deletion**: Before removing any function, method, or resource ID, search the entire workspace (`grep` / `find_usages`) to ensure it is not referenced by:
  * Tests, instrumentation, or previews
  * Android Manifest, accessibility configs, or layout XMLs
  * Reflection or dynamic service bindings
* **Keep Cleanups Atomic**: Put large refactors and dead-code removals in a dedicated `refactor:` or `chore:` commit—never mix cleanup with new feature commits.

## Testing & Build Verification

* Write meaningful unit tests for domain logic, ViewModels, and state transformations (`JUnit`, `MockK`, `Turbine`).
* Test Compose UI components using `ComposeTestRule` where applicable.
* Run `./gradlew assembleDebug` or `gradle_build` to verify compilation before declaring UI or code tasks done.
* Never claim something works unless it was actually verified.

## Git Workflow

* **Atomic Commits Only**: Never bundle multiple logical concerns into one commit.
  * Separate Data/Repository layer changes from UI Composable changes.
  * Separate Service/Accessibility logic from Activity/Manifest updates.
  * Separate Refactors/Fixes from New Features.
  * Co-commit Tests with their feature or keep in a dedicated test commit.
* **Mandatory Pre-Commit Plan**: Before committing, always present a numbered **Commit Breakdown Plan** showing:
  * Commit message (`type(scope): message`)
  * Target files
  * Purpose
  * **Wait for explicit user approval before staging or committing.**
* Write meaningful, conventional commit messages (`feat:`, `fix:`, `refactor:`, `chore:`, `test:`).
* Review the diff before committing.
* Never discard existing user changes.
* **Always ask before committing.**
* **Always ask before pushing.**
* Never force-push without explicit permission.

## Safety

* Ask before destructive or irreversible actions.
* Never delete or overwrite user work without permission.
* Never expose or commit secrets or keystores (`*.jks`, `*.keystore`).

## Completion

Before declaring a task done:

1. Verify the changes via Gradle sync / build.
2. Run relevant checks (unit tests, linter, builds).
3. Review the diff.
4. Report what changed and what was tested.
5. Ask before committing or pushing.
