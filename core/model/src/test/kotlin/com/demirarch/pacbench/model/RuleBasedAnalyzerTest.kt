package com.demirarch.pacbench.model

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedAnalyzerTest {
    @Test
    fun analysisDoesNotInventMissingMetrics() {
        val findings = RuleBasedAnalyzer.analyze((0L..9L).map { SampleData(timestamp = it * 1000, fps = 60.0) })
        assertTrue(findings.none { it.type == AnalysisType.GPU_BOTTLENECK || it.type == AnalysisType.THERMAL_THROTTLING })
    }

    @Test
    fun severeThermalWindowIsReported() {
        val findings = RuleBasedAnalyzer.analyze((0L..4L).map { SampleData(timestamp = it * 1000, thermalState = 3) })
        assertTrue(findings.any { it.type == AnalysisType.THERMAL_THROTTLING && it.confidence == Confidence.HIGH })
    }
}
