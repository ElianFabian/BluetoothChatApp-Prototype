package com.elianfabian.bluetoothchatapp.home.domain

import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {
	val isBluetoothSupported: Boolean
	val canEnableBluetooth: Boolean

	val state: StateFlow<BluetoothState>
	val isScanning: StateFlow<Boolean>
	val scannedDevices: StateFlow<List<BluetoothDevice>>
	val pairedDevices: StateFlow<List<BluetoothDevice>>
	val connectionState: StateFlow<DeviceConnectionState>

	fun startScan()
	fun stopScan()

	fun startBluetoothServer(): Flow<ConnectionResult>
	fun connectToDevice(device: BluetoothDevice): Flow<ConnectionResult>

	suspend fun trySendMessage(message: String): BluetoothMessage?



	fun closeConnection()

	fun release()

	enum class BluetoothState {
		On,
		TurningOn,
		Off,
		TurningOff;

		val isOn: Boolean get() = this == On
	}

	enum class DeviceConnectionState {
		Connected,
		Connecting,
		Disconnected,
	}

	sealed interface ConnectionResult {
		data object ConnectionEstablished : ConnectionResult
		data object DeviceIsNotPaired : ConnectionResult
		data object CouldNotConnect : ConnectionResult
		data class Message(val message: BluetoothMessage) : ConnectionResult
	}
}
