package com.elianfabian.bluetoothchatapp.common.domain

import kotlinx.coroutines.flow.StateFlow

interface AndroidHelper {

	val bluetoothName: StateFlow<String>

	fun showToast(message: String)

	fun openAppSettings()
	fun openBluetoothSettings()
	fun openDeviceInfoSettings()

	suspend fun showMakeDeviceDiscoverableDialog(): Boolean
	suspend fun showEnableBluetoothDialog(): Boolean

	fun closeKeyboard()
}
