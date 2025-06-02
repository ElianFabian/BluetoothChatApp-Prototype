package com.elianfabian.bluetoothchatapp_prototype.common.domain

import kotlinx.coroutines.flow.StateFlow

interface PermissionController {
	val state: StateFlow<PermissionState>

	suspend fun request(): PermissionState
}

interface MultiplePermissionController {
	val state: StateFlow<Map<String, PermissionState>>

	suspend fun request(): Map<String, PermissionState>
}

enum class PermissionState {
	NotDetermined,
	Granted,
	Denied,
	PermanentlyDenied;

	val isGranted: Boolean get() = this == Granted
}
