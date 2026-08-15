package com.demirarch.pacbench.access

import android.content.Context
import com.demirarch.pacbench.metrics.MetricEngine
import com.demirarch.pacbench.metrics.NormalMetricProvider
import com.demirarch.pacbench.metrics.RootMetricProvider
import com.demirarch.pacbench.metrics.ShizukuMetricProvider
import com.demirarch.pacbench.metrics.TcpLatencyEndpoint
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricProvider
import com.demirarch.pacbench.model.MetricSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetricEngineFactory @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    fun create(
        targetPackage: String,
        pingEndpoint: String,
        explicitSurfaceLayer: String? = null,
    ): MetricEngineHandle {
        require(targetPackage.isNotBlank()) { "Target package must not be blank" }

        val closeables = mutableListOf<AutoCloseable>()
        val providers = buildList<MetricProvider> {
            add(NormalMetricProvider(appContext, parseEndpoint(pingEndpoint)))
            runCatching {
                ShizukuMetricProvider(
                    context = appContext,
                    targetPackage = targetPackage,
                    explicitSurfaceLayer = explicitSurfaceLayer,
                )
            }.getOrNull()?.let { provider ->
                add(provider)
                closeables += provider
            }
            runCatching {
                RootMetricProvider(
                    targetPackage = targetPackage,
                    explicitSurfaceLayer = explicitSurfaceLayer,
                )
            }.getOrNull()?.let { provider ->
                add(provider)
                closeables += provider
            }
        }
        return MetricEngineHandle(MetricEngine(providers), closeables)
    }

    private fun parseEndpoint(rawValue: String): TcpLatencyEndpoint? = runCatching {
        val value = rawValue.trim()
        if (value.isEmpty()) return@runCatching null

        val (host, port) = when {
            "://" in value -> {
                val uri = URI(value)
                require(!uri.host.isNullOrBlank()) { "Latency endpoint has no host" }
                uri.host to uri.port.takeIf { it > 0 }
            }
            value.startsWith("[") -> {
                val bracket = value.indexOf(']')
                require(bracket > 1) { "Invalid IPv6 latency endpoint" }
                val host = value.substring(1, bracket)
                val suffix = value.substring(bracket + 1)
                host to suffix.removePrefix(":").takeIf(String::isNotBlank)?.toIntOrNull()
            }
            value.count { it == ':' } == 1 && value.substringAfterLast(':').toIntOrNull() != null ->
                value.substringBeforeLast(':') to value.substringAfterLast(':').toInt()
            else -> value to null
        }
        TcpLatencyEndpoint(host = host, port = port ?: DEFAULT_LATENCY_PORT)
    }.getOrNull()

    private companion object {
        const val DEFAULT_LATENCY_PORT = 443
    }
}

class MetricEngineHandle internal constructor(
    val engine: MetricEngine,
    private val closeables: List<AutoCloseable>,
) : AutoCloseable {
    suspend fun sample(metrics: Set<MetricId> = MetricId.entries.toSet()): MetricSnapshot =
        engine.sample(metrics)

    suspend fun capabilities(
        metrics: Set<MetricId> = MetricId.entries.toSet(),
    ): Map<MetricId, List<MetricCapability>> = engine.capabilities(metrics)

    override fun close() {
        closeables.asReversed().forEach { closeable -> runCatching(closeable::close) }
    }
}
