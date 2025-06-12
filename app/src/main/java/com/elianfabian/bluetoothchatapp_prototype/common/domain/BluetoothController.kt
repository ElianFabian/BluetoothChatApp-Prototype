package com.elianfabian.bluetoothchatapp_prototype.common.domain

import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BluetoothController {

	val bluetoothDeviceName: StateFlow<String?>
	val isBluetoothSupported: Boolean
	val canEnableBluetooth: Boolean

	val state: StateFlow<BluetoothState>
	val isScanning: StateFlow<Boolean>
	val isWaitingForConnection: StateFlow<Boolean>

	val devices: StateFlow<List<BluetoothDevice>>
	val events: SharedFlow<Event>

	fun setBluetoothDeviceName(name: String): Boolean

	fun startScan(): Boolean
	fun stopScan(): Boolean

	suspend fun startBluetoothServer(): ConnectionResult
	suspend fun startInsecureBluetoothServer(): ConnectionResult
	fun stopBluetoothServer()
	suspend fun connectToDevice(address: String): ConnectionResult
	suspend fun connectToDeviceInsecurely(address: String): ConnectionResult
	suspend fun disconnectFromDevice(address: String): Boolean
	suspend fun cancelConnectionAttempt(address: String): Boolean
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

	sealed interface Event {
		data class OnDeviceConnected(
			val connectedDevice: BluetoothDevice,
			// This indicates whether you connected to a device as a server or intentionally chose which one to connect to
			val manuallyConnected: Boolean,
		) : Event

		data class OnDeviceDisconnected(
			val disconnectedDevice: BluetoothDevice,
			// This indicates if was the current user who intentionally disconnected the device
			// In the case the user intentionally disconnects from the device but it was the other device
			// who disconnected from us it will count as not manually disconnected
			val manuallyDisconnected: Boolean,
		) : Event
	}
}
