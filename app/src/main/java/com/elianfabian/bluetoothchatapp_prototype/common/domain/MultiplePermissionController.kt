package com.elianfabian.bluetoothchatapp_prototype.common.domain

import kotlinx.coroutines.flow.StateFlow

interface MultiplePermissionController {
	val state: StateFlow<Map<String, PermissionState>>

	fun request()

	suspend fun awaitResult(): Map<String, PermissionState>
}
