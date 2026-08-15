package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.MetricId
import com.demirarch.pacbench.model.MetricStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuSysfsAdaptersTest {
    @Test
    fun kgslUsesBusyTotalRatioAndHertz() = runTest {
        val access = FakeSystemFileAccess(
            files = mapOf(
                "/sys/class/kgsl/kgsl-3d0/gpubusy" to "1 4",
                "/sys/class/kgsl/kgsl-3d0/gpuclk" to "8000000",
            ),
        )
        val adapter = QualcommKgslSysfsAdapter()

        assertEquals(25.0, adapter.read(MetricId.GPU_USAGE, access)!!.value, 0.0)
        assertEquals(8.0, adapter.read(MetricId.GPU_FREQUENCY, access)!!.value, 0.0)
    }

    @Test
    fun maliIsDiscoveredByDevfreqIdentity() = runTest {
        val directory = "/sys/class/devfreq/1c40000.mali"
        val access = FakeSystemFileAccess(
            files = mapOf(
                "$directory/name" to "mali",
                "$directory/load" to "42@800000000",
                "$directory/cur_freq" to "8000000",
            ),
            directories = mapOf("/sys/class/devfreq" to listOf(directory)),
        )
        val adapter = MaliDevfreqSysfsAdapter()

        assertEquals(42.0, adapter.read(MetricId.GPU_USAGE, access)!!.value, 0.0)
        assertEquals(8.0, adapter.read(MetricId.GPU_FREQUENCY, access)!!.value, 0.0)
        assertNull(adapter.read(MetricId.CPU_USAGE, access))
    }

    private class FakeSystemFileAccess(
        private val files: Map<String, String> = emptyMap(),
        private val directories: Map<String, List<String>> = emptyMap(),
    ) : SystemFileAccess {
        override suspend fun readText(path: String): FileAccessResult<String> = files[path]
            ?.let { FileAccessResult.available(it) }
            ?: FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, "$path is absent")

        override suspend fun listPaths(path: String): FileAccessResult<List<String>> = directories[path]
            ?.let { FileAccessResult.available(it) }
            ?: FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, "$path is absent")
    }
}
