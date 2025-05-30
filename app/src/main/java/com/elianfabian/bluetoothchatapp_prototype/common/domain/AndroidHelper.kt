package com.elianfabian.bluetoothchatapp_prototype.common.domain

interface AndroidHelper {

	fun showToast(message: String)

	fun openAppSettings()
	fun openBluetoothSettings()
	fun openDeviceInfoSettings()

	suspend fun showMakeDeviceDiscoverableDialog(): Boolean
	suspend fun showEnableBluetoothDialog(): Boolean
	suspend fun showEnableLocationDialog(): Boolean

	fun closeKeyboard()
}
