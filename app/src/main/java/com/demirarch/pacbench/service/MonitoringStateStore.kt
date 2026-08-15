package com.demirarch.pacbench.service

import com.demirarch.pacbench.model.MetricSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MonitoringState {
    data object Idle : MonitoringState

    data class Starting(
        val targetPackage: String?,
        val automaticDetection: Boolean,
    ) : MonitoringState

    data class Running(
        val sessionId: Long,
        val targetPackage: String,
        val targetLabel: String,
        val latestSnapshot: MetricSnapshot,
        val acceptedSamples: Long,
        val droppedSamples: Long,
        val hudVisible: Boolean,
    ) : MonitoringState

    data class Failed(val reason: String) : MonitoringState
}

@Singleton
class MonitoringStateStore @Inject constructor() {
    private val mutableState = MutableStateFlow<MonitoringState>(MonitoringState.Idle)
    val state: StateFlow<MonitoringState> = mutableState.asStateFlow()

    internal fun update(value: MonitoringState) {
        mutableState.value = value
    }
}
