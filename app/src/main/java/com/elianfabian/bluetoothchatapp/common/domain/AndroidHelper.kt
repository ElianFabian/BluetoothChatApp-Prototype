package com.elianfabian.bluetoothchatapp.common.domain

import kotlinx.coroutines.flow.StateFlow

interface AndroidHelper {

	val bluetoothName: StateFlow<String>

	fun showToast(message: String)

	fun openAppSettings()
	fun openBluetoothSettings()
	fun openDeviceInfoSettings()

	fun makeDeviceDiscoverable(callback: (accepted: Boolean) -> Unit)
	fun showEnableBluetoothDialog(callback: (enabled: Boolean) -> Unit)

	fun closeKeyboard()
}
