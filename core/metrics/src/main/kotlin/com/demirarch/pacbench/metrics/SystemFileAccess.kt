package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.MetricStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FileAccessResult<T>(
    val value: T? = null,
    val status: MetricStatus,
    val reason: String? = null,
) {
    val available: Boolean get() = status == MetricStatus.AVAILABLE && value != null

    companion object {
        fun <T> available(value: T): FileAccessResult<T> = FileAccessResult(value, MetricStatus.AVAILABLE)
        fun <T> unavailable(status: MetricStatus, reason: String): FileAccessResult<T> =
            FileAccessResult(status = status, reason = reason)
    }
}

interface SystemFileAccess {
    suspend fun readText(path: String): FileAccessResult<String>
    suspend fun listPaths(path: String): FileAccessResult<List<String>>
}

internal data class AccessFailure(val status: MetricStatus, val reason: String)

internal class ProbingSystemFileAccess(
    private val delegate: SystemFileAccess,
) : SystemFileAccess {
    private var strongestFailure: AccessFailure? = null

    override suspend fun readText(path: String): FileAccessResult<String> = delegate.readText(path).also { record(it) }

    override suspend fun listPaths(path: String): FileAccessResult<List<String>> = delegate.listPaths(path).also { record(it) }

    fun failure(defaultReason: String): AccessFailure = strongestFailure
        ?: AccessFailure(MetricStatus.SOURCE_ABSENT, defaultReason)

    private fun record(result: FileAccessResult<*>) {
        if (result.available) return
        val candidate = AccessFailure(result.status, result.reason ?: "System file access failed")
        if (failureWeight(candidate.status) > failureWeight(strongestFailure?.status)) {
            strongestFailure = candidate
        }
    }

    private fun failureWeight(status: MetricStatus?): Int = when (status) {
        MetricStatus.PERMISSION_DENIED -> 5
        MetricStatus.SCHEMA_MISMATCH, MetricStatus.INVALID_VALUE -> 4
        MetricStatus.STALE, MetricStatus.COUNTER_RESET -> 3
        MetricStatus.UNSUPPORTED_API -> 2
        MetricStatus.SOURCE_ABSENT -> 1
        MetricStatus.AVAILABLE, null -> 0
        MetricStatus.TARGET_AMBIGUOUS -> 0
    }
}

class LocalSystemFileAccess : SystemFileAccess {
    override suspend fun readText(path: String): FileAccessResult<String> = withContext(Dispatchers.IO) {
        if (!CommandPolicy.isAllowedSystemPath(path)) {
            return@withContext FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, "Path is not allowlisted")
        }
        val file = File(path)
        try {
            if (!file.isFile) {
                FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, "$path is absent")
            } else if (!file.canRead()) {
                FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, "$path is not readable")
            } else {
                file.inputStream().use { stream ->
                    val bytes = stream.readNBytes(MAX_FILE_BYTES + 1)
                    if (bytes.size > MAX_FILE_BYTES) {
                        FileAccessResult.unavailable(MetricStatus.SCHEMA_MISMATCH, "$path exceeds the read limit")
                    } else {
                        FileAccessResult.available(bytes.toString(Charsets.UTF_8))
                    }
                }
            }
        } catch (error: SecurityException) {
            FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, error.message ?: "$path read denied")
        } catch (error: Exception) {
            FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, error.message ?: "$path read failed")
        }
    }

    override suspend fun listPaths(path: String): FileAccessResult<List<String>> = withContext(Dispatchers.IO) {
        if (!CommandPolicy.isAllowedSystemPath(path)) {
            return@withContext FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, "Path is not allowlisted")
        }
        val directory = File(path)
        try {
            if (!directory.isDirectory) {
                FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, "$path is absent")
            } else if (!directory.canRead()) {
                FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, "$path is not readable")
            } else {
                val children = directory.listFiles()
                    ?: return@withContext FileAccessResult.unavailable(
                        MetricStatus.PERMISSION_DENIED,
                        "$path could not be listed",
                    )
                FileAccessResult.available(children.map { it.path }.sorted())
            }
        } catch (error: SecurityException) {
            FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, error.message ?: "$path list denied")
        } catch (error: Exception) {
            FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, error.message ?: "$path list failed")
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 512 * 1024
    }
}

class CommandSystemFileAccess(
    private val executor: CommandExecutor,
) : SystemFileAccess {
    override suspend fun readText(path: String): FileAccessResult<String> =
        executor.execute(SafeCommand.ReadFile(path)).toFileResult { it }

    override suspend fun listPaths(path: String): FileAccessResult<List<String>> =
        executor.execute(SafeCommand.ListDirectory(path)).toFileResult { output ->
            output.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { child -> if (child.startsWith('/')) child else "${path.trimEnd('/')}/$child" }
                .filter(CommandPolicy::isAllowedSystemPath)
                .toList()
        }

    private fun <T> CommandResult.toFileResult(transform: (String) -> T): FileAccessResult<T> = when {
        successful -> FileAccessResult.available(transform(stdout))
        status == CommandStatus.PERMISSION_DENIED -> FileAccessResult.unavailable(
            MetricStatus.PERMISSION_DENIED,
            reason ?: "Command permission denied",
        )
        status == CommandStatus.OUTPUT_LIMIT -> FileAccessResult.unavailable(
            MetricStatus.SCHEMA_MISMATCH,
            reason ?: "Command output exceeded its limit",
        )
        status == CommandStatus.TIMED_OUT -> FileAccessResult.unavailable(
            MetricStatus.STALE,
            reason ?: "Command timed out",
        )
        else -> FileAccessResult.unavailable(
            MetricStatus.SOURCE_ABSENT,
            reason ?: "Command source unavailable",
        )
    }
}
