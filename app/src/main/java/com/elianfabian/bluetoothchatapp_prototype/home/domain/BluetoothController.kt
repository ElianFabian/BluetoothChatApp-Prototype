package com.elianfabian.bluetoothchatapp_prototype.home.domain

import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {

	// TODO: adapter.setName()
	val bluetoothDeviceName: StateFlow<String?>
	val isBluetoothSupported: Boolean
	val canEnableBluetooth: Boolean

	val state: StateFlow<BluetoothState>
	val isScanning: StateFlow<Boolean>
	val isWaitingForConnection: StateFlow<Boolean>

	val devices: StateFlow<List<BluetoothDevice>>

	fun setBluetoothDeviceName(name: String): Boolean

	fun startScan(): Boolean
	fun stopScan(): Boolean

	suspend fun startBluetoothServer(): ConnectionResult
	suspend fun startInsecureBluetoothServer(): ConnectionResult
	fun stopBluetoothServer()
	suspend fun connectToDevice(address: String): ConnectionResult
	suspend fun connectToDeviceInsecure(address: String): ConnectionResult
	suspend fun disconnectFromDevice(address: String): Boolean
	fun listenMessagesFrom(address: String): Flow<BluetoothMessage>

	suspend fun trySendMessage(address: String, message: String): BluetoothMessage?

	enum class BluetoothState {
		On,
		TurningOn,
		Off,
		TurningOff;

		val isOn: Boolean get() = this == On
	}

	sealed interface ConnectionResult {
		data class ConnectionEstablished(val device: BluetoothDevice) : ConnectionResult
		data object CouldNotConnect : ConnectionResult
	}
}
