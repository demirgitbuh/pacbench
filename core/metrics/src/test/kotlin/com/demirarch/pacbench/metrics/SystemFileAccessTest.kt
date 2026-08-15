package com.demirarch.pacbench.metrics

import com.demirarch.pacbench.model.MetricStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemFileAccessTest {
    @Test
    fun permissionDenialOutranksAbsentOptionalPathsDuringProbe() = runTest {
        val access = ProbingSystemFileAccess(
            object : SystemFileAccess {
                override suspend fun readText(path: String): FileAccessResult<String> =
                    FileAccessResult.unavailable(MetricStatus.PERMISSION_DENIED, "$path denied")

                override suspend fun listPaths(path: String): FileAccessResult<List<String>> =
                    FileAccessResult.unavailable(MetricStatus.SOURCE_ABSENT, "$path absent")
            },
        )

        access.listPaths("/sys/class/devfreq")
        access.readText("/sys/class/kgsl/kgsl-3d0/gpubusy")

        assertEquals(MetricStatus.PERMISSION_DENIED, access.failure("default").status)
    }
}
