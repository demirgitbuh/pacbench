package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.AccessMode
import java.io.File

class RootCommandExecutor(
    private val timeoutMillis: Long = 5_000,
) : CommandExecutor {
    override val accessMode: AccessMode = AccessMode.ROOT

    @Volatile
    private var resolvedSu: String? = null

    override suspend fun availability(): ExecutorAvailability {
        val result = execute(SafeCommand.ReadFile("/proc/stat"))
        return ExecutorAvailability(
            available = result.successful,
            permissionDenied = result.status == CommandStatus.PERMISSION_DENIED,
            reason = result.reason,
        )
    }

    override suspend fun execute(command: SafeCommand): CommandResult {
        val commandArgv = try {
            CommandPolicy.argv(command)
        } catch (error: IllegalArgumentException) {
            return CommandResult(CommandStatus.PERMISSION_DENIED, reason = error.message)
        }
        val su = resolvedSu ?: findSu()?.also { resolvedSu = it }
            ?: return CommandResult(CommandStatus.UNAVAILABLE, reason = "No allowlisted su executable is present")

        val shellCommand = commandArgv.joinToString(" ") { argument -> shellQuote(argument) }
        val result = ProcessRunner.run(listOf(su, "-c", shellCommand), timeoutMillis)
        return if (result.status == CommandStatus.FAILED && looksLikeRootDenial(result)) {
            result.copy(status = CommandStatus.PERMISSION_DENIED, reason = result.reason ?: "Root access denied")
        } else {
            result
        }
    }

    private fun findSu(): String? = SU_PATHS.firstOrNull { path ->
        runCatching { File(path).isFile && File(path).canExecute() }.getOrDefault(false)
    }

    private fun shellQuote(argument: String): String = "'${argument.replace("'", "'\\''")}'"

    private fun looksLikeRootDenial(result: CommandResult): Boolean {
        val text = "${result.stdout}\n${result.stderr}\n${result.reason.orEmpty()}".lowercase()
        return "denied" in text || "not granted" in text || "not allowed" in text || "unauthorized" in text
    }

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )
    }
}
