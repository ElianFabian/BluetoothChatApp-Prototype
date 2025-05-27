package com.elianfabian.bluetoothchatapp.home.presentation

import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp.common.domain.PermissionState
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothDevice

data class HomeState(
	val bluetoothDeviceName: String,
	val isBluetoothSupported: Boolean,
	val isBluetoothOn: Boolean,
	val isScanning: Boolean = false,
	val connectionState: BluetoothController.ConnectionState = BluetoothController.ConnectionState.Disconnected,
	val connectedDevices: List<BluetoothDevice> = emptyList(),
	val pairedDevices: List<BluetoothDevice> = emptyList(),
	val scannedDevices: List<BluetoothDevice> = emptyList(),
	val permissionState: List<PermissionState> = emptyList(),
	val permissionDialog: PermissionDialogState? = null,
	val messages: List<BluetoothMessage> = emptyList(),
	val enteredMessage: String = "",
	val enteredTargetDeviceAddress: String = "",
) {
	data class PermissionDialogState(
		val title: String,
		val message: String,
		val actionName: String,
		val onAction: () -> Unit,
		val onDismissRequest: () -> Unit,
	)
}
