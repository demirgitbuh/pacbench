package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.AccessMode

sealed interface SafeCommand {
    data class ReadFile(val path: String) : SafeCommand
    data class ListDirectory(val path: String) : SafeCommand
    data object SurfaceFlingerLayers : SafeCommand
    data class SurfaceFlingerLatency(val layer: String) : SafeCommand
    data class GfxInfoFramestats(val packageName: String) : SafeCommand
}

enum class CommandStatus {
    SUCCESS,
    PERMISSION_DENIED,
    UNAVAILABLE,
    TIMED_OUT,
    OUTPUT_LIMIT,
    FAILED,
}

data class CommandResult(
    val status: CommandStatus,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val reason: String? = null,
) {
    val successful: Boolean get() = status == CommandStatus.SUCCESS && exitCode == 0
}

data class ExecutorAvailability(
    val available: Boolean,
    val permissionDenied: Boolean = false,
    val reason: String? = null,
)

interface CommandExecutor : AutoCloseable {
    val accessMode: AccessMode

    suspend fun availability(): ExecutorAvailability

    suspend fun execute(command: SafeCommand): CommandResult

    override fun close() = Unit
}

object CommandPolicy {
    private val packageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val safePathCharacters = Regex("/[A-Za-z0-9_./:@+,-]+")
    private val exactReadablePaths = setOf(
        "/proc/stat",
        "/sys/devices/system/cpu",
        "/sys/class/thermal",
        "/sys/class/kgsl",
        "/sys/class/devfreq",
        "/sys/class/drm",
        "/sys/kernel/gpu",
    )
    private val readablePrefixes = listOf(
        "/sys/devices/system/cpu/",
        "/sys/class/thermal/",
        "/sys/class/kgsl/",
        "/sys/class/devfreq/",
        "/sys/class/drm/",
        "/sys/kernel/gpu/",
    )

    fun argv(command: SafeCommand): List<String> = when (command) {
        is SafeCommand.ReadFile -> listOf("/system/bin/cat", validatedPath(command.path))
        is SafeCommand.ListDirectory -> listOf("/system/bin/ls", "-1", validatedPath(command.path))
        SafeCommand.SurfaceFlingerLayers -> listOf("/system/bin/dumpsys", "SurfaceFlinger", "--list")
        is SafeCommand.SurfaceFlingerLatency -> listOf(
            "/system/bin/dumpsys",
            "SurfaceFlinger",
            "--latency",
            validatedLayer(command.layer),
        )
        is SafeCommand.GfxInfoFramestats -> listOf(
            "/system/bin/dumpsys",
            "gfxinfo",
            validatedPackage(command.packageName),
            "framestats",
        )
    }

    fun isAllowedSystemPath(path: String): Boolean = try {
        validatedPath(path)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun validatedPath(path: String): String {
        require(path.length <= 512 && safePathCharacters.matches(path)) { "Invalid system path" }
        require("//" !in path && !path.endsWith('/')) { "System path must be normalized" }
        require(path.split('/').none { it == ".." || it == "." }) { "Path traversal is not allowed" }
        require(path in exactReadablePaths || readablePrefixes.any(path::startsWith)) {
            "System path is outside the metric allowlist"
        }
        return path
    }

    private fun validatedPackage(value: String): String {
        require(value.length <= 255 && packageName.matches(value)) { "Invalid Android package name" }
        return value
    }

    private fun validatedLayer(value: String): String {
        require(value.isNotBlank() && value.length <= 1024) { "Invalid SurfaceFlinger layer" }
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid SurfaceFlinger layer" }
        return value
    }
}
