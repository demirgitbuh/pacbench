package com.demirarch.pacbench.metrics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.demirarch.pacbench.model.AccessMode
import com.demirarch.pacbench.model.MetricCapability
import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricProvider
import com.demirarch.pacbench.model.MetricReading
import com.demirarch.pacbench.model.MetricSource
import com.demirarch.pacbench.model.MetricStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class TcpLatencyEndpoint(
    val host: String,
    val port: Int = 443,
    val timeoutMillis: Int = 1_500,
) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "TCP latency host is invalid" }
        require(host.none { it == '\u0000' || it == '\n' || it == '\r' }) { "TCP latency host is invalid" }
        require(port in 1..65535) { "TCP latency port is invalid" }
        require(timeoutMillis in 100..30_000) { "TCP latency timeout is invalid" }
    }
}

class NormalMetricProvider(
    context: Context,
    private val latencyEndpoint: TcpLatencyEndpoint? = null,
    fileAccess: SystemFileAccess = LocalSystemFileAccess(),
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : MetricProvider {
    override val accessMode: AccessMode = AccessMode.NORMAL
    override val priority: Int = 300

    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val system = SystemMetricSampler(
        fileAccess,
        AccessMode.NORMAL,
        procSource = MetricSource.PROCFS,
        sysfsSource = MetricSource.SYSFS,
    )
    private val uid = Process.myUid()
    private val traffic = OwnUidTrafficSampler(uid)

    override suspend fun probe(metric: MetricId): MetricCapability = when (metric) {
        MetricId.FPS, MetricId.FRAME_TIME -> capability(
            metric,
            MetricStatus.SOURCE_ABSENT,
            null,
            "Normal APIs expose display refresh rate, not application FPS; no value is substituted",
        )
        MetricId.CPU_USAGE,
        MetricId.CPU_FREQUENCY,
        MetricId.CPU_TEMPERATURE,
        MetricId.GPU_USAGE,
        MetricId.GPU_FREQUENCY,
        MetricId.GPU_TEMPERATURE,
        -> system.probe(metric)
        MetricId.RAM_USED, MetricId.RAM_AVAILABLE -> {
            val manager = activityManager
                ?: return capability(metric, MetricStatus.SOURCE_ABSENT, MetricSource.ANDROID_API, "ActivityManager is absent")
            val memory = ActivityManager.MemoryInfo()
            runCatching { manager.getMemoryInfo(memory) }.fold(
                onSuccess = {
                    if (memory.totalMem > 0 && memory.availMem in 0..memory.totalMem) {
                        capability(metric, MetricStatus.AVAILABLE, MetricSource.ANDROID_API)
                    } else {
                        capability(metric, MetricStatus.INVALID_VALUE, MetricSource.ANDROID_API, "ActivityManager memory values are invalid")
                    }
                },
                onFailure = { error ->
                    capability(metric, MetricStatus.SOURCE_ABSENT, MetricSource.ANDROID_API, error.message ?: "Memory query failed")
                },
            )
        }
        MetricId.BATTERY_LEVEL,
        MetricId.BATTERY_TEMPERATURE,
        MetricId.VOLTAGE,
        MetricId.CURRENT,
        MetricId.POWER,
        -> readBattery(metric).toCapability(accessMode)
        MetricId.DOWNLOAD_RATE, MetricId.UPLOAD_RATE -> {
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
                capability(
                    metric,
                    MetricStatus.UNSUPPORTED_API,
                    MetricSource.ANDROID_API,
                    "TrafficStats does not expose counters for own UID $uid",
                )
            } else if (rx < 0 || tx < 0) {
                capability(
                    metric,
                    MetricStatus.INVALID_VALUE,
                    MetricSource.ANDROID_API,
                    "TrafficStats returned invalid counters for own UID $uid",
                )
            } else {
                capability(metric, MetricStatus.AVAILABLE, MetricSource.ANDROID_API)
            }
        }
        MetricId.PING -> if (latencyEndpoint == null) {
            capability(
                metric,
                MetricStatus.SOURCE_ABSENT,
                MetricSource.ANDROID_API,
                "No TCP latency endpoint is configured",
            )
        } else {
            capability(metric, MetricStatus.AVAILABLE, MetricSource.ANDROID_API)
        }
        MetricId.THERMAL_STATUS -> if (powerManager == null) {
            capability(metric, MetricStatus.SOURCE_ABSENT, MetricSource.ANDROID_API, "PowerManager is absent")
        } else {
            capability(metric, MetricStatus.AVAILABLE, MetricSource.ANDROID_API)
        }
    }

    override suspend fun read(metric: MetricId): MetricReading = when (metric) {
        MetricId.FPS, MetricId.FRAME_TIME -> MetricReading.unavailable(
            metric,
            MetricStatus.SOURCE_ABSENT,
            "No normal-access real frame source is available",
        )
        MetricId.CPU_USAGE,
        MetricId.CPU_FREQUENCY,
        MetricId.CPU_TEMPERATURE,
        MetricId.GPU_USAGE,
        MetricId.GPU_FREQUENCY,
        MetricId.GPU_TEMPERATURE,
        -> system.read(metric)
        MetricId.RAM_USED, MetricId.RAM_AVAILABLE -> readMemory(metric)
        MetricId.BATTERY_LEVEL,
        MetricId.BATTERY_TEMPERATURE,
        MetricId.VOLTAGE,
        MetricId.CURRENT,
        MetricId.POWER,
        -> readBattery(metric)
        MetricId.DOWNLOAD_RATE, MetricId.UPLOAD_RATE -> readTraffic(metric)
        MetricId.PING -> readTcpLatency()
        MetricId.THERMAL_STATUS -> readThermalStatus()
    }

    private fun readMemory(metric: MetricId): MetricReading {
        val manager = activityManager ?: return MetricReading.unavailable(
            metric,
            MetricStatus.SOURCE_ABSENT,
            "ActivityManager is absent",
            MetricSource.ANDROID_API,
        )
        val memory = ActivityManager.MemoryInfo()
        return try {
            manager.getMemoryInfo(memory)
            if (memory.totalMem <= 0 || memory.availMem !in 0..memory.totalMem) {
                MetricReading.unavailable(
                    metric,
                    MetricStatus.INVALID_VALUE,
                    "ActivityManager memory values are invalid",
                    MetricSource.ANDROID_API,
                )
            } else {
                val bytes = if (metric == MetricId.RAM_AVAILABLE) memory.availMem else memory.totalMem - memory.availMem
                MetricReading.available(
                    metric,
                    bytes.toDouble() / BYTES_PER_GB,
                    MetricSource.ANDROID_API,
                    "ActivityManager.MemoryInfo",
                )
            }
        } catch (error: SecurityException) {
            MetricReading.unavailable(
                metric,
                MetricStatus.PERMISSION_DENIED,
                error.message ?: "Memory query denied",
                MetricSource.ANDROID_API,
            )
        }
    }

    private fun readBattery(metric: MetricId): MetricReading {
        val intent = batteryIntent()
        return when (metric) {
            MetricId.BATTERY_LEVEL -> {
                val level = intent?.intExtra(BatteryManager.EXTRA_LEVEL)
                val scale = intent?.intExtra(BatteryManager.EXTRA_SCALE)
                val percent = BatteryCalculations.levelPercent(level, scale) ?: run {
                    batteryManager?.safeIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toDouble()
                }
                batteryReading(metric, percent?.takeIf { it in 0.0..100.0 }, "Battery level is unavailable")
            }
            MetricId.BATTERY_TEMPERATURE -> {
                val tenthsCelsius = intent?.intExtra(BatteryManager.EXTRA_TEMPERATURE)
                batteryReading(
                    metric,
                    BatteryCalculations.temperatureCelsius(tenthsCelsius),
                    "Battery temperature is unavailable",
                )
            }
            MetricId.VOLTAGE -> {
                val millivolts = intent?.intExtra(BatteryManager.EXTRA_VOLTAGE)
                batteryReading(
                    metric,
                    BatteryCalculations.voltageVolts(millivolts),
                    "Battery voltage is unavailable",
                )
            }
            MetricId.CURRENT -> batteryReading(
                metric,
                currentAmperes(),
                "BATTERY_PROPERTY_CURRENT_NOW is unsupported",
                SIGNED_CURRENT_IDENTITY,
            )
            MetricId.POWER -> {
                val millivolts = intent?.intExtra(BatteryManager.EXTRA_VOLTAGE)
                val microamperes = currentMicroamperes()
                val watts = BatteryCalculations.powerWatts(millivolts, microamperes)
                batteryReading(metric, watts, "Battery voltage or current is unavailable", SIGNED_POWER_IDENTITY)
            }
            else -> MetricReading.unavailable(
                metric,
                MetricStatus.SOURCE_ABSENT,
                "$metric is not a battery metric",
                MetricSource.ANDROID_API,
            )
        }
    }

    private fun batteryIntent(): Intent? = try {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: SecurityException) {
        null
    }

    private fun Intent.intExtra(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE } else null

    private fun BatteryManager.safeIntProperty(property: Int): Int? = runCatching {
        getIntProperty(property).takeUnless { it == Int.MIN_VALUE }
    }.getOrNull()

    private fun currentMicroamperes(): Long? {
        val manager = batteryManager ?: return null
        return runCatching { manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
            .getOrNull()
            ?.takeUnless { it == Long.MIN_VALUE }
    }

    private fun currentAmperes(): Double? = BatteryCalculations.currentAmperes(currentMicroamperes())

    private fun batteryReading(
        metric: MetricId,
        value: Double?,
        unavailableReason: String,
        identity: String = "BatteryManager/ACTION_BATTERY_CHANGED",
    ): MetricReading = if (value?.isFinite() == true) {
        MetricReading.available(metric, value, MetricSource.ANDROID_API, identity)
    } else {
        MetricReading.unavailable(metric, MetricStatus.UNSUPPORTED_API, unavailableReason, MetricSource.ANDROID_API)
    }

    private fun readTraffic(metric: MetricId): MetricReading {
        val received = TrafficStats.getUidRxBytes(uid)
        val transmitted = TrafficStats.getUidTxBytes(uid)
        if (received == TrafficStats.UNSUPPORTED.toLong() || transmitted == TrafficStats.UNSUPPORTED.toLong()) {
            return MetricReading.unavailable(
                metric,
                MetricStatus.UNSUPPORTED_API,
                "TrafficStats does not expose counters for own UID $uid",
                MetricSource.ANDROID_API,
            )
        }
        return when (val result = traffic.add(elapsedRealtimeNanos(), received, transmitted)) {
            is NetworkRateResult.Value -> MetricReading.available(
                metric,
                if (metric == MetricId.DOWNLOAD_RATE) result.downloadMbps else result.uploadMbps,
                MetricSource.ANDROID_API,
                "own-uid:$uid",
            )
            NetworkRateResult.FirstSample -> MetricReading.unavailable(
                metric,
                MetricStatus.STALE,
                "A second TrafficStats sample is required for own UID $uid",
                MetricSource.ANDROID_API,
            )
            NetworkRateResult.CounterReset -> MetricReading.unavailable(
                metric,
                MetricStatus.COUNTER_RESET,
                "TrafficStats counters reset for own UID $uid",
                MetricSource.ANDROID_API,
            )
            NetworkRateResult.Invalid -> MetricReading.unavailable(
                metric,
                MetricStatus.INVALID_VALUE,
                "TrafficStats delta is invalid for own UID $uid",
                MetricSource.ANDROID_API,
            )
        }
    }

    private suspend fun readTcpLatency(): MetricReading {
        val endpoint = latencyEndpoint ?: return MetricReading.unavailable(
            MetricId.PING,
            MetricStatus.SOURCE_ABSENT,
            "No TCP latency endpoint is configured",
            MetricSource.ANDROID_API,
        )
        return withContext(Dispatchers.IO) {
            val started = elapsedRealtimeNanos()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), endpoint.timeoutMillis)
                }
                val elapsedMillis = (elapsedRealtimeNanos() - started).toDouble() / NANOS_PER_MILLISECOND
                if (elapsedMillis.isFinite() && elapsedMillis >= 0.0) {
                    MetricReading.available(
                        MetricId.PING,
                        elapsedMillis,
                        MetricSource.ANDROID_API,
                        "tcp://${endpoint.host}:${endpoint.port}",
                    )
                } else {
                    MetricReading.unavailable(
                        MetricId.PING,
                        MetricStatus.INVALID_VALUE,
                        "Monotonic TCP latency interval is invalid",
                        MetricSource.ANDROID_API,
                    )
                }
            } catch (error: SocketTimeoutException) {
                MetricReading.unavailable(
                    MetricId.PING,
                    MetricStatus.STALE,
                    "TCP connect timed out after ${endpoint.timeoutMillis} ms",
                    MetricSource.ANDROID_API,
                )
            } catch (error: SecurityException) {
                MetricReading.unavailable(
                    MetricId.PING,
                    MetricStatus.PERMISSION_DENIED,
                    error.message ?: "TCP connect denied",
                    MetricSource.ANDROID_API,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                MetricReading.unavailable(
                    MetricId.PING,
                    MetricStatus.SOURCE_ABSENT,
                    error.message ?: "TCP endpoint unavailable",
                    MetricSource.ANDROID_API,
                )
            }
        }
    }

    private fun readThermalStatus(): MetricReading {
        val status = powerManager?.currentThermalStatus ?: return MetricReading.unavailable(
            MetricId.THERMAL_STATUS,
            MetricStatus.SOURCE_ABSENT,
            "PowerManager is absent",
            MetricSource.ANDROID_API,
        )
        return if (status in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_SHUTDOWN) {
            MetricReading.available(
                MetricId.THERMAL_STATUS,
                status.toDouble(),
                MetricSource.ANDROID_API,
                "PowerManager.currentThermalStatus",
            )
        } else {
            MetricReading.unavailable(
                MetricId.THERMAL_STATUS,
                MetricStatus.INVALID_VALUE,
                "PowerManager returned unknown thermal status $status",
                MetricSource.ANDROID_API,
            )
        }
    }

    private fun capability(
        metric: MetricId,
        status: MetricStatus,
        source: MetricSource?,
        reason: String? = null,
    ) = MetricCapability(metric, accessMode, status, source, reason)

    private fun MetricReading.toCapability(mode: AccessMode) =
        MetricCapability(metric, mode, status, source, reason)

    private companion object {
        const val BYTES_PER_GB = 1_000_000_000.0
        const val NANOS_PER_MILLISECOND = 1_000_000.0
        const val SIGNED_CURRENT_IDENTITY = "battery-terminal:positive=charging,negative=discharging"
        const val SIGNED_POWER_IDENTITY = "battery-terminal-power:positive=charging,negative=discharging"
    }
}

private class OwnUidTrafficSampler(private val uid: Int) {
    private val calculator = NetworkRateCalculator()
    private var cachedAtNanos = Long.MIN_VALUE
    private var cached: NetworkRateResult? = null

    @Synchronized
    fun add(timestampNanos: Long, receivedBytes: Long, transmittedBytes: Long): NetworkRateResult {
        cached?.takeIf {
            timestampNanos >= cachedAtNanos && timestampNanos - cachedAtNanos <= CACHE_NANOS
        }?.let { return it }
        val result = calculator.add(TrafficCounters(timestampNanos, receivedBytes, transmittedBytes))
        cachedAtNanos = timestampNanos
        cached = result
        return result
    }

    override fun toString(): String = "OwnUidTrafficSampler(uid=$uid)"

    private companion object {
        const val CACHE_NANOS = 100_000_000L
    }
}
