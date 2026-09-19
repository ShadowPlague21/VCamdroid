package com.darusc.vcamdroid.capabilities

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository pattern - single source of truth, cached, observable
 * FAANG principle: UI never talks to CameraManager directly
 */
class CapabilityRepository private constructor() {

    private val _reportFlow = MutableStateFlow<DeviceCapabilityReport?>(null)
    val reportFlow: StateFlow<DeviceCapabilityReport?> = _reportFlow

    private var cachedReport: DeviceCapabilityReport? = null
    private var lastProbeTime = 0L
    private val cacheTtlMs = 30_000L // 30 sec cache

    fun getCached(): DeviceCapabilityReport? = cachedReport

    suspend fun refresh(context: Context): DeviceCapabilityReport {
        val now = System.currentTimeMillis()
        if (cachedReport != null && now - lastProbeTime < cacheTtlMs) {
            return cachedReport!!
        }
        val report = CameraCapabilityProbe.probe(context.applicationContext)
        cachedReport = report
        lastProbeTime = now
        _reportFlow.value = report
        return report
    }

    fun refreshSync(context: Context): DeviceCapabilityReport {
        val now = System.currentTimeMillis()
        if (cachedReport != null && now - lastProbeTime < cacheTtlMs) {
            return cachedReport!!
        }
        val report = CameraCapabilityProbe.probeSync(context.applicationContext)
        cachedReport = report
        lastProbeTime = now
        _reportFlow.value = report
        return report
    }

    fun invalidate() {
        cachedReport = null
        lastProbeTime = 0L
    }

    companion object {
        @Volatile
        private var INSTANCE: CapabilityRepository? = null

        fun getInstance(): CapabilityRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CapabilityRepository().also { INSTANCE = it }
            }
        }
    }
}
