package com.demirarch.pacbench.metrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal object ProcessRunner {
    suspend fun run(
        argv: List<String>,
        timeoutMillis: Long = 5_000,
        maxOutputBytes: Int = 256 * 1024,
    ): CommandResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "Empty command" }
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(false).start()
        } catch (error: SecurityException) {
            return@withContext CommandResult(
                CommandStatus.PERMISSION_DENIED,
                reason = error.message ?: "Process start denied",
            )
        } catch (error: Exception) {
            return@withContext CommandResult(
                CommandStatus.UNAVAILABLE,
                reason = error.message ?: "Process could not be started",
            )
        }

        try {
            coroutineScope {
                val stdout = async { readBounded(process.inputStream, maxOutputBytes) }
                val stderr = async { readBounded(process.errorStream, maxOutputBytes) }
                val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                }
                val out = stdout.await()
                val err = stderr.await()
                if (!finished) {
                    CommandResult(
                        CommandStatus.TIMED_OUT,
                        stdout = out.text,
                        stderr = err.text,
                        reason = "Command timed out after $timeoutMillis ms",
                    )
                } else if (out.truncated || err.truncated) {
                    CommandResult(
                        CommandStatus.OUTPUT_LIMIT,
                        process.exitValue(),
                        out.text,
                        err.text,
                        "Command output exceeded $maxOutputBytes bytes",
                    )
                } else {
                    val exitCode = process.exitValue()
                    val denied = exitCode != 0 && looksDenied(err.text)
                    CommandResult(
                        status = when {
                            exitCode == 0 -> CommandStatus.SUCCESS
                            denied -> CommandStatus.PERMISSION_DENIED
                            else -> CommandStatus.FAILED
                        },
                        exitCode = exitCode,
                        stdout = out.text,
                        stderr = err.text,
                        reason = if (exitCode == 0) null else err.text.trim().ifBlank { "Command exited with $exitCode" },
                    )
                }
            }
        } finally {
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readBounded(stream: InputStream, limit: Int): CapturedOutput {
        val buffer = ByteArray(8 * 1024)
        val output = ByteArray(limit)
        var saved = 0
        var truncated = false
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            val writable = minOf(count, limit - saved)
            if (writable > 0) {
                buffer.copyInto(output, saved, 0, writable)
                saved += writable
            }
            if (writable < count) truncated = true
        }
        return CapturedOutput(String(output, 0, saved, Charsets.UTF_8), truncated)
    }

    private fun looksDenied(stderr: String): Boolean {
        val text = stderr.lowercase()
        return "permission denied" in text || "not allowed" in text || "access denied" in text ||
            "root access" in text && "denied" in text
    }

    private data class CapturedOutput(val text: String, val truncated: Boolean)
}
