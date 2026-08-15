# Changelog

All notable changes to PacBench are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-08-15

### Added

- Android 13+ project configuration with `:app`, `:core:model`, `:core:metrics`, and `:core:data` modules.
- Typed contracts for metric values, data sources, access modes, and explicit unavailable states.
- Models for performance samples, session exports, HUD widgets, and built-in HUD presets.
- FPS-low, frametime, power, session-summary, and rule-based analysis calculations.
- Room entities, DAOs, and repositories for games, sessions, samples, and HUD presets.
- DataStore-backed settings with sampling, metric, display, retention, database-cap, and ping-endpoint preferences.
- Local JSON and CSV session-export serialization.
- Foreground monitoring service with batched Room writes, interrupted-session recovery, and notification controls.
- Runtime Android, Shizuku, root, SurfaceFlinger, gfxinfo, Qualcomm, Mali, and Xclipse capability providers.
- Responsive Compose game, HUD designer, report, graph, comparison, access, onboarding, and settings screens.
- PNG, JPEG, and paginated PDF session reports shared through Android's FileProvider and Sharesheet.
- Unit and Android test coverage for calculations, missing-metric analysis behavior, database/export behavior, and serialization.
- English and Turkish application resources.
- GPLv3 licensing, privacy documentation, release notes, and Android release automation.
- Signed APK/AAB tag builds with signature verification and SHA-256 checksums.

### Limitations

- Android does not expose another application's real presented FPS to an ordinary app; FPS therefore requires a working privileged frame backend.
- GPU and thermal sysfs nodes remain device-, permission-, kernel-, and vendor-dependent.
- TCP connection latency is not ICMP ping, and own-UID traffic is not target-game per-UID traffic.
- Missing readings remain unavailable rather than estimated.
