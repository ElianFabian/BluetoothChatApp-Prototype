package com.elianfabian.bluetoothchatapp_prototype.home.presentation

import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp_prototype.home.domain.BluetoothDevice

data class HomeState(
	val isBluetoothSupported: Boolean,
	val isBluetoothOn: Boolean,
	val enteredBluetoothDeviceName: String? = null,
	val bluetoothDeviceName: String? = null,
	val isWaitingForConnection: Boolean = false,
	val isScanning: Boolean = false,
	val pairedDevices: List<BluetoothDevice> = emptyList(),
	val scannedDevices: List<BluetoothDevice> = emptyList(),
	val connectedDevices: List<BluetoothDevice> = emptyList(),
	val selectedDevice: BluetoothDevice? = null,
	val permissionDialog: PermissionDialogState? = null,
	val messages: List<BluetoothMessage> = emptyList(),
	val enteredMessage: String = "",
	val useSecureConnection: Boolean = true,
) {
	data class PermissionDialogState(
		val title: String,
		val message: String,
		val actionName: String,
		val onAction: () -> Unit,
		val onDismissRequest: () -> Unit,
	)
}
