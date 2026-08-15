package com.demirarch.pacbench.metrics

import android.content.Context
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricProvider
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSource
import com.demirarch.pacbench.model.MetricStatus

open class CommandMetricProvider(
    protected val executor: CommandExecutor,
    targetPackage: String? = null,
    explicitSurfaceLayer: String? = null,
    override val priority: Int,
) : MetricProvider, AutoCloseable {
    final override val accessMode: AccessMode = executor.accessMode

    private val shellSource = when (accessMode) {
        AccessMode.SHIZUKU -> MetricSource.SHIZUKU_SHELL
        AccessMode.ROOT -> MetricSource.ROOT_SHELL
        AccessMode.NORMAL -> error("CommandMetricProvider requires privileged access")
    }
    private val system = SystemMetricSampler(
        CommandSystemFileAccess(executor),
        accessMode,
        procSource = shellSource,
        sysfsSource = shellSource,
    )
    private val frames = BestFrameMetricBackend(
        SurfaceFlingerLatencyBackend(executor, targetPackage, explicitSurfaceLayer),
        GfxInfoFramestatsBackend(executor, targetPackage),
    )

    override suspend fun probe(metric: MetricId): MetricCapability {
        if (metric !in SUPPORTED_METRICS) {
            return MetricCapability(
                metric,
                accessMode,
                MetricStatus.SOURCE_ABSENT,
                reason = "$metric is provided by Android APIs rather than privileged shell",
            )
        }
        val availability = executor.availability()
        if (!availability.available) {
            return MetricCapability(
                metric,
                accessMode,
                if (availability.permissionDenied) MetricStatus.PERMISSION_DENIED else MetricStatus.SOURCE_ABSENT,
                shellSource,
                availability.reason ?: "$accessMode executor unavailable",
            )
        }
        if (metric == MetricId.FPS || metric == MetricId.FRAME_TIME) {
            val result = frames.probe()
            return MetricCapability(metric, accessMode, result.status, result.source, result.reason)
        }
        return system.probe(metric)
    }

    override suspend fun read(metric: MetricId): MetricReading = when (metric) {
        MetricId.FPS, MetricId.FRAME_TIME -> frames.read(metric)
        in SYSTEM_METRICS -> system.read(metric)
        else -> MetricReading.unavailable(
            metric,
            MetricStatus.SOURCE_ABSENT,
            "$metric is not supported by this privileged provider",
            shellSource,
        )
    }

    override fun close() = executor.close()

    private companion object {
        val SYSTEM_METRICS = setOf(
            MetricId.CPU_USAGE,
            MetricId.CPU_FREQUENCY,
            MetricId.CPU_TEMPERATURE,
            MetricId.GPU_USAGE,
            MetricId.GPU_FREQUENCY,
            MetricId.GPU_TEMPERATURE,
        )
        val SUPPORTED_METRICS = SYSTEM_METRICS + MetricId.FPS + MetricId.FRAME_TIME
    }
}

class ShizukuMetricProvider(
    context: Context,
    targetPackage: String? = null,
    explicitSurfaceLayer: String? = null,
    val shizukuExecutor: ShizukuCommandExecutor = ShizukuCommandExecutor(context),
) : CommandMetricProvider(
    shizukuExecutor,
    targetPackage,
    explicitSurfaceLayer,
    priority = 200,
) {
    fun requestPermission(): Boolean = shizukuExecutor.requestPermission()
    val permissionState: ShizukuPermissionState get() = shizukuExecutor.permissionState
}

class RootMetricProvider(
    targetPackage: String? = null,
    explicitSurfaceLayer: String? = null,
    val rootExecutor: RootCommandExecutor = RootCommandExecutor(),
) : CommandMetricProvider(
    rootExecutor,
    targetPackage,
    explicitSurfaceLayer,
    priority = 100,
)
