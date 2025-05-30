package com.elianfabian.bluetoothchatapp_prototype.common.domain

import kotlinx.coroutines.flow.StateFlow

interface PermissionController {
	val state: StateFlow<PermissionState>

	fun request()

	suspend fun awaitResult(): PermissionState
}

enum class PermissionState {
	NotDetermined,
	Granted,
	Denied,
	PermanentlyDenied,
}
