package com.elianfabian.bluetoothchatapp_prototype.home.presentation

import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp_prototype.common.domain.BluetoothDevice

sealed interface HomeAction {
	data object StartScan : HomeAction
	data object StopScan : HomeAction
	data object StartServer : HomeAction
	data object StopServer : HomeAction
	data object OpenBluetoothSettings : HomeAction
	data object OpenDeviceInfoSettings : HomeAction
	data object MakeDeviceDiscoverable : HomeAction
	data object SendMessage : HomeAction
	data class EnterMessage(val message: String) : HomeAction
	data class ClickScannedDevice(val device: BluetoothDevice) : HomeAction
	data class ClickPairedDevice(val device: BluetoothDevice) : HomeAction
	data class LongClickPairedDevice(val device: BluetoothDevice) : HomeAction
	data class LongClickScannedDevice(val device: BluetoothDevice) : HomeAction
	data class ClickMessage(val message: BluetoothMessage) : HomeAction
	data object EditBluetoothDeviceName : HomeAction
	data object SaveBluetoothDeviceName : HomeAction
	data class EnterBluetoothDeviceName(val bluetoothDeviceName: String) : HomeAction
	data class CheckUseSecureConnection(val enabled: Boolean) : HomeAction
	data class SelectTargetDeviceToMessage(val connectedDevice: BluetoothDevice) : HomeAction
	data object SelectAllDevicesToMessage : HomeAction
	data object EnableBluetooth : HomeAction
}
