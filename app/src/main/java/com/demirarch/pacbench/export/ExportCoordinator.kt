package com.demirarch.pacbench.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.demirarch.pacbench.data.export.SessionExportSerializer
import com.demirarch.pacbench.data.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionExportFormat(
    val extension: String,
    val mimeType: String,
) {
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    PDF("pdf", "application/pdf"),
}

data class ExportedSessionReport(
    val sessionId: Long,
    val format: SessionExportFormat,
    val file: File,
    val contentUri: Uri,
)

@Singleton
class ExportCoordinator @Inject constructor(
    @ApplicationContext context: Context,
    private val sessionRepository: SessionRepository,
) {
    private val appContext = context.applicationContext
    private val renderer = SessionReportRenderer()
    private val exportMutex = Mutex()

    suspend fun export(sessionId: Long, format: SessionExportFormat): ExportedSessionReport =
        withContext(Dispatchers.IO) {
            exportMutex.withLock {
                require(sessionId > 0L) { "Session ID must be positive" }
                val rows = requireNotNull(sessionRepository.getSessionWithRows(sessionId)) {
                    "Session $sessionId does not exist"
                }
                val exportDirectory = File(appContext.cacheDir, EXPORT_DIRECTORY).apply {
                    check(exists() || mkdirs()) { "Export directory could not be created" }
                }
                val packagePart = rows.game.packageName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(MAX_FILE_PART_LENGTH)
                val target = File(
                    exportDirectory,
                    "pacbench-${packagePart}-${rows.session.id}.${format.extension}",
                )

                writeAtomically(target) { temporaryFile ->
                    when (format) {
                        SessionExportFormat.CSV -> temporaryFile.writeUtf8(SessionExportSerializer.toCsv(rows))
                        SessionExportFormat.JSON -> temporaryFile.writeUtf8(SessionExportSerializer.toJson(rows))
                        SessionExportFormat.PNG -> renderer.writeBitmap(
                            rows,
                            temporaryFile,
                            Bitmap.CompressFormat.PNG,
                            PNG_QUALITY,
                        )
                        SessionExportFormat.JPEG -> renderer.writeBitmap(
                            rows,
                            temporaryFile,
                            Bitmap.CompressFormat.JPEG,
                            JPEG_QUALITY,
                        )
                        SessionExportFormat.PDF -> renderer.writePdf(rows, temporaryFile)
                    }
                }

                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX",
                    target,
                )
                ExportedSessionReport(sessionId, format, target, uri)
            }
        }

    fun createShareIntent(report: ExportedSessionReport): Intent = Intent(Intent.ACTION_SEND).apply {
        type = report.format.mimeType
        putExtra(Intent.EXTRA_STREAM, report.contentUri)
        clipData = ClipData.newUri(appContext.contentResolver, report.file.name, report.contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun createShareChooser(
        report: ExportedSessionReport,
        title: CharSequence = "Share PacBench report",
    ): Intent = Intent.createChooser(createShareIntent(report), title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun writeAtomically(target: File, writer: (File) -> Unit) {
        val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            writer(temporary)
            check(temporary.isFile && temporary.length() > 0L) { "Export produced no data" }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun File.writeUtf8(content: String) {
        outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(content) }
    }

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
        const val MAX_FILE_PART_LENGTH = 96
        const val PNG_QUALITY = 100
        const val JPEG_QUALITY = 92
    }
}
