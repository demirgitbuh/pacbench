# PacBench Privacy Policy

Effective date: August 15, 2026

This policy describes the PacBench `0.1.0` open-source project and the behavior represented by the source in this repository. A distributor can modify the software under the GPL; review the source and the distributor's policy before installing a third-party build.

## Summary

PacBench is designed as a local-first performance monitor. The project contains no PacBench-operated account service, advertising SDK, analytics SDK, telemetry endpoint, or cloud synchronization service. Session information remains in the app's local storage unless the user initiates an export. The `0.1.0` source includes runtime metric collectors, an optional configured TCP latency check, Room/DataStore storage, and a user-directed Android Sharesheet export flow.

## Data Represented By PacBench

The source defines models that can hold:

- Timestamps and a selected application's display name and package name.
- Device manufacturer and model.
- The selected access mode: normal, Shizuku, or root.
- Performance readings such as FPS, frame time, CPU, GPU, memory, battery, power, thermal, network-rate, and ping values.
- Data-source identifiers, availability states, and diagnostic reasons.
- HUD presets and settings, including a configured ping destination.
- Statistical summaries and rule-based findings.

Collection depends on enabled metrics, runtime permissions, access mode, and actual device support. Inaccessible fields are stored as null and reported as unavailable.

## Local Storage And Retention

The `:core:data` module implements a Room database named `pacbench.db` for game metadata, session metadata, metric samples, and HUD presets. It also implements a Preferences DataStore named `pacbench_settings` for sampling, enabled metrics, display choices, retention, database-cap, and ping-destination settings. These components use the application's private Android storage when integrated into the app. Android application sandboxing ordinarily prevents other non-privileged apps from directly reading that private storage.

The application exposes deletion by session and a user-triggered retention cleanup. The configured database cap is informational in v0.1.0 and is not silently enforced. Clearing PacBench's app data or uninstalling PacBench ordinarily deletes its private local data. Files previously shared outside the app sandbox are controlled by the receiving application.

The official application manifest disables Android backup and excludes application roots from cloud backup and device transfer.

## Optional Network Ping

When ping sampling is enabled, `0.1.0` measures TCP connection setup time to port 443 of the configured host. This can send network packets and DNS queries. The destination and network intermediaries may observe the device IP address and requested host. PacBench does not include session metrics in that connection.

Normal device traffic counters do not themselves require PacBench to upload captured sessions. A build that adds a different network service must disclose that behavior separately.

## User-Initiated Export

The source implements CSV, JSON, PNG, JPEG, and PDF reports for stored sessions. An export can contain the selected application identity, device manufacturer/model, Android/app versions, access mode, session times, notes, a data-quality summary, and captured samples. Export is written to private cache and shared only after a user selects an Android Sharesheet destination.

Export must be initiated by the user. Once the user sends a file to another app or service, the data leaves PacBench's private storage and is governed by the destination's security and privacy practices. Users should inspect exported data before sharing it publicly because device and application identifiers may be included.

## Shizuku And Root

The project implements normal, Shizuku, and root access modes. Privileged execution accepts only fixed command types and allowlisted procfs/sysfs paths, packages, and SurfaceFlinger layer arguments.

Shizuku is a separate service controlled by the user. If privileged collection is implemented and authorized, commands can run through that service with elevated Android shell capabilities. Shizuku and Android can process authorization and command traffic locally on the device according to their own implementations.

Root mode requires a rooted device and authorization from the device's superuser manager. If implemented and authorized, root can permit access to protected procfs/sysfs files and system commands. Granting root materially expands the data and system resources available to PacBench. Root or Shizuku access does not inherently upload data, but it increases the impact of a compromised or modified build.

Users should deny elevated access unless they understand the build, trust its source, and need the privileged measurements.

## Data Sharing

The official source contains no code for selling personal information, serving ads, or sending analytics to PacBench maintainers. Data can leave the device only through behavior added by a build, an optional active network operation such as ping, a user-directed export, or operating-system/device services such as backup. Third-party Android, Shizuku, root-management, destination, and backup software is governed by its own policy.

## Children's Privacy

PacBench does not provide accounts, social features, advertising, or a service directed to children. The project maintainers do not knowingly collect children's personal information through a PacBench-operated service because no such service exists.

## Security

No software or storage mechanism is guaranteed secure. Use release checksums, obtain builds from a trusted source, keep the device updated, and apply the least-privilege principle. Exported reports and elevated modes deserve particular care.

## Policy Changes

Material changes will be documented in this file and dated above. Release notes may summarize changes that affect data handling.

## Contact

Privacy questions and vulnerability reports can be submitted through the project's GitHub issue tracker. Avoid posting exported session data, device identifiers, secrets, or security-sensitive details in a public issue.
