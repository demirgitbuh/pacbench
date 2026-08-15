# PacBench v0.1.0

PacBench v0.1.0 is the first Android 13+ release. It combines real runtime metric providers, a configurable overlay HUD, local sessions, reports, deterministic analysis, and export with an explicit rule: missing or inaccessible measurements remain unavailable.

## Highlights

- Android 13 (API 33) minimum, targeting API 36.
- Typed models for frame, CPU, GPU, memory, battery, power, thermal, and network observations.
- Explicit source and quality statuses for unsupported APIs, denied permissions, absent sources, schema mismatches, stale data, counter resets, invalid values, and ambiguous targets.
- FPS-low, frametime, power, session-summary, and rule-based analysis calculations.
- Serializable session-export and configurable HUD-preset schemas.
- Local Room storage and repositories for games, sessions, samples, and HUD presets.
- DataStore-backed settings and local JSON/CSV session serialization.
- User-started foreground monitoring with Room batch writes and interrupted-session recovery.
- Game discovery/manual packages, Usage Access detection, overlay HUD, and full-screen HUD designer.
- Interactive combined/separate/grid graphs, comparison, deterministic analysis, and data-quality reporting.
- CSV, JSON, PNG, JPEG, and PDF sharing.
- English and Turkish resources.
- Reproducibly named signed APK/AAB release artifacts with SHA-256 checksums.

## Access Modes

- **Normal:** public Android memory, battery, thermal, own-UID traffic, TCP latency, and readable procfs/sysfs sources.
- **Shizuku:** user-authorized allowlisted shell reads, SurfaceFlinger/gfxinfo frame timing, and vendor probes.
- **Root:** optional allowlisted superuser access for protected diagnostics and vendor nodes.

Selecting or representing an access mode does not grant permission and does not guarantee that a device exposes the requested metric.

## Important Limitations

- Android does not provide another application's real presented FPS through ordinary public APIs. FPS is unavailable unless SurfaceFlinger or gfxinfo access works for the target.
- CPU/GPU frequencies, utilization, and temperatures are inherently device- and vendor-dependent. Missing nodes or incompatible schemas must be reported as unavailable.
- Network latency is a TCP port 443 connection measurement, not ICMP. Normal traffic counters describe PacBench's own UID, not arbitrary games.
- Shizuku must be installed, running, and authorized. Root requires a rooted device and separate superuser approval.

## Artifacts

- `PacBench-v0.1.0.apk`
- `PacBench-v0.1.0.aab`
- `PacBench-v0.1.0-SHA256SUMS.txt`

Verify downloads against the SHA-256 checksum file before installation. The APK is intended for direct installation; the AAB is intended for an Android app-store publishing pipeline.

PacBench is distributed under the GNU General Public License, version 3.
