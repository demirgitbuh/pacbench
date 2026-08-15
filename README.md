# PacBench

PacBench is an Android performance-monitoring project focused on explicit data quality. Its model covers frame pacing, CPU, GPU, memory, battery, thermal, network, and power observations while preserving why a reading is unavailable instead of substituting a guessed value.

PacBench 0.1.0 targets Android 13 and newer (API 33+). It is a local-first native Kotlin application with a foreground monitoring service, optional game overlay, Room reports, a configurable HUD designer, real metric providers, deterministic analysis, and user-initiated exports.

## Current Scope

Implemented in `0.1.0`:

- Typed metric readings with source, access mode, status, reason, and source identity.
- Session models and summaries, including FPS lows and frametime statistics.
- Rule-based findings that only use available observations.
- Room storage and repositories for games, sessions, samples, and HUD presets.
- DataStore-backed local settings.
- JSON and CSV session-export serialization.
- Normal Android, Shizuku, and root runtime providers with defensive capability probing.
- User-started foreground monitoring, Usage Access game detection, and touch-through overlay HUD.
- Game discovery, package registration, launch control, responsive report charts, and session comparison.
- PNG, JPEG, and paginated PDF report generation from stored session rows.
- English and Turkish Android resources.
- Unit tests for calculations, analysis behavior, and serialization.

## Metrics And Availability

The metric model represents FPS, frame time, CPU utilization/frequency/temperature, GPU utilization/frequency/temperature, used and available RAM, battery level/temperature/voltage/current, power, download/upload rates, ping, and Android thermal status. Representation in the model does not mean that a collector is implemented or that a device exposes the value.

| Access class | Implemented sources | Expected unavailable behavior |
| --- | --- | --- |
| Public Android APIs | RAM via `ActivityManager`, battery/current/power, thermal status, own-UID traffic deltas, TCP connect latency, and readable procfs/sysfs | A specific non-`AVAILABLE` status and no numeric value |
| Shizuku | Allowlisted shell reads, SurfaceFlinger latency, gfxinfo framestats, CPU and vendor GPU sysfs probes | Permission, binder, layer ambiguity, schema, and absent-source failures remain explicit |
| Root | The same fixed allowlisted diagnostics through an optional `su` executor | Root denial or missing nodes never fall back to fabricated values |
| Vendor adapters | Qualcomm KGSL, Mali devfreq, and Xclipse/DRM-style runtime path discovery | Unknown paths and formats are rejected rather than inferred from another device |

An available reading must contain a finite value. Unavailable readings can distinguish unsupported APIs, denied permission, absent sources, schema mismatches, stale data, counter resets, invalid values, and ambiguous targets. Conversion to session samples keeps unavailable values as `null`, and analysis rules do not invent findings from missing metrics.

## Access Modes

- **Normal** represents access available to an ordinary Android application. It does not imply that every public metric is available on every device.
- **Shizuku** represents commands delegated through a separately installed and authorized Shizuku service. Declaring this mode does not grant permission; the user must control authorization.
- **Root** represents commands executed with superuser privileges on a rooted device. Root is optional and carries materially greater security risk.

Shizuku and root may make protected operating-system interfaces reachable, but they cannot make missing vendor nodes or incompatible output schemas reliable. PacBench should expose those cases as unavailable.

## Architecture

The Gradle project has four modules:

| Module          | Responsibility                                                                                             |
| --------------- | ---------------------------------------------------------------------------------------------------------- |
| `:app`          | Android application packaging, Compose/Hilt integration, resources, and release signing configuration      |
| `:core:model`   | Metric, session, HUD, export, statistics, and analysis models                                              |
| `:core:metrics` | Android, Shizuku, root, frame-timing, procfs/sysfs, and vendor metric providers                            |
| `:core:data`    | Room database, DAOs, repositories, DataStore settings, HUD persistence, and JSON/CSV session serialization |

## Building

Prerequisites:

- JDK 17
- Android SDK 36
- Gradle 8.13

The repository includes a Gradle 8.13 wrapper:

```bash
./gradlew --no-daemon lint test assembleDebug
./gradlew --no-daemon assembleRelease bundleRelease
```

Release signing is optional for local verification. The Android build reads these environment variables when all four are set:

```text
PACBENCH_KEYSTORE_PATH
PACBENCH_KEYSTORE_PASSWORD
PACBENCH_KEY_ALIAS
PACBENCH_KEY_PASSWORD
```

Without them, `assembleRelease` and `bundleRelease` produce unsigned verification outputs. Do not treat an unsigned output as an official release.

## Release Automation

GitHub Actions runs lint, unit tests, a debug build, and unsigned release verification for normal pushes and pull requests. A tag matching `v*` additionally builds signed APK and AAB files, verifies their signatures, creates SHA-256 checksums, uploads the artifacts, and creates or updates the matching GitHub Release.

The release workflow expects these repository secrets:

| Secret                     | Purpose                         |
| -------------------------- | ------------------------------- |
| `PACBENCH_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `PACBENCH_KEYSTORE_PASSWORD` | Keystore password             |
| `PACBENCH_KEY_ALIAS`       | Signing key alias               |
| `PACBENCH_KEY_PASSWORD`    | Signing key password            |

For tag `v0.1.0`, release files are named `PacBench-v0.1.0.apk`, `PacBench-v0.1.0.aab`, and `PacBench-v0.1.0-SHA256SUMS.txt`.

## Privacy And Security

PacBench is intended to keep captured session data on the device unless the user deliberately exports it. An optional ping feature would contact the configured destination and therefore disclose the device IP address to that destination and intervening network providers. Exported files leave the app sandbox and are controlled by the selected destination. See [PRIVACY.md](PRIVACY.md) for the precise current-source status and data-handling details.

Only enable Shizuku or root for software you have reviewed and on a device you control. Elevated access expands what a compromised application or dependency could read or execute.

## License

PacBench is licensed under the GNU General Public License, version 3. See [LICENSE](LICENSE).
