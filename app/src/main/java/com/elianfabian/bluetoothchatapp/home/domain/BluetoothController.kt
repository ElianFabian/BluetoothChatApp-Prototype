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
	val connectionState: StateFlow<ConnectionState>
	val pairingState: StateFlow<PairingState>

	fun startScan()
	fun stopScan()

	suspend fun startBluetoothServer(): ConnectionResult
	suspend fun connectToDevice(device: BluetoothDevice): ConnectionResult
	fun listenMessages(): Flow<BluetoothMessage>

	suspend fun trySendMessage(message: String): BluetoothMessage?


	fun closeConnection()

	enum class BluetoothState {
		On,
		TurningOn,
		Off,
		TurningOff;

		val isOn: Boolean get() = this == On
	}

	enum class ConnectionState {
		Connected,
		Connecting,
		Disconnected,
	}

	enum class PairingState {
		Paired,
		Pairing,
		None,
	}

	sealed interface ConnectionResult {
		data object ConnectionEstablished : ConnectionResult
		data object DeviceIsNotPaired : ConnectionResult
		data object CouldNotConnect : ConnectionResult
	}
}
