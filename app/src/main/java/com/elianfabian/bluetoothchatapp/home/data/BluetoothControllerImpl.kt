package com.elianfabian.bluetoothchatapp.home.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp.common.data.MainActivityHolder
import com.elianfabian.bluetoothchatapp.common.util.simplestack.callbacks.ApplicationBackgroundStateChangeCallback
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothDevice
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothControllerImpl(
	private val mainActivityHolder: MainActivityHolder,
	private val registeredScope: CoroutineScope,
) : BluetoothController,
	ScopedServices.Registered,
	ApplicationBackgroundStateChangeCallback {

	private val context: Context get() = mainActivityHolder.mainActivity

	private val _bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: throw IllegalStateException("Couldn't get the BluetoothManager")
	private val _bluetoothAdapter: BluetoothAdapter? = _bluetoothManager.adapter

	private val _bluetoothState = MutableStateFlow(
		if (_bluetoothAdapter?.isEnabled == true) {
			BluetoothController.BluetoothState.On
		}
		else BluetoothController.BluetoothState.Off
	)
	override val state = _bluetoothState.asStateFlow()

	override val canEnableBluetooth: Boolean
		@SuppressLint("InlinedApi")
		get() = if (Build.VERSION.SDK_INT >= 31) {
			context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
		}
		else true

	override val isBluetoothSupported: Boolean
		get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

	private val _isScanning = MutableStateFlow(
		if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 31) {
			_bluetoothAdapter?.isDiscovering == true
		}
		else false
	)
	override val isScanning = _isScanning.asStateFlow()

	private val _isWaitingForConnection = MutableStateFlow(false)
	override val isWaitingForConnection = _isWaitingForConnection.asStateFlow()

	private val _devices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val devices = _devices.asStateFlow()

	private val _deviceFoundReceiver = DeviceFoundBroadcastReceiver(
		onDeviceFound = { androidDeviceFound ->
			_devices.update { devices ->
				val existingDevice = devices.find { it.address == androidDeviceFound.address }
				if (existingDevice != null) {
					devices.map { device ->
						if (device.address == androidDeviceFound.address) {
							device.copy(
								name = androidDeviceFound.name ?: device.name,
								pairingState = getPairingStateFromAndroidDevice(androidDeviceFound),
							)
						}
						else device
					}
				}
				else {
					devices + BluetoothDevice(
						name = androidDeviceFound.name,
						address = androidDeviceFound.address,
						pairingState = getPairingStateFromAndroidDevice(androidDeviceFound),
						connectionState = BluetoothDevice.ConnectionState.Disconnected,
					)
				}
			}
		}
	)

	private val _discoveryStateChangeReceiver = BluetoothDiscoveryStateChangeBroadcastReceiver(
		onDiscoveryStateChange = { isDiscovering ->
			_isScanning.value = isDiscovering
		}
	)

	private val _bluetoothStateChangeReceiver = BluetoothStateChangeBroadcastReceiver(
		onStateChange = { state ->
			when (state) {
				BluetoothAdapter.STATE_ON -> {
					_bluetoothState.value = BluetoothController.BluetoothState.On
				}
				BluetoothAdapter.STATE_TURNING_ON -> {
					_bluetoothState.value = BluetoothController.BluetoothState.TurningOn
				}
				BluetoothAdapter.STATE_OFF -> {
					_bluetoothState.value = BluetoothController.BluetoothState.Off
				}
				BluetoothAdapter.STATE_TURNING_OFF -> {
					_bluetoothState.value = BluetoothController.BluetoothState.TurningOff
				}
			}
		}
	)

	private val _bluetoothDeviceConnectionReceiver = BluetoothDeviceConnectionBroadcastReceiver(
		onConnectionStateChange = { androidDevice, isConnected ->
			// When we try to connect to a paired device, this callback executes with isConnected to true and after some small time (around 4s)
			// it executes again with isConnected to false

			if (!isConnected) {
				_devices.update { devices ->
					devices.map { device ->
						if (device.address == androidDevice.address) {
							device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						}
						else device
					}
				}
			}
		}
	)

	private val _bondStateChangeReceiver = DeviceBondStateChangeBroadcastReceiver(
		onStateChange = { androidDevice, state ->
			_devices.update { devices ->
				devices.map { existingDevice ->
					if (existingDevice.address == androidDevice.address) {
						existingDevice.copy(
							name = androidDevice.name ?: existingDevice.name,
							pairingState = getPairingStateFromAndroidDevice(androidDevice),
						)
					}
					else existingDevice
				}
			}
		}
	)

	private val _clientSocketByAddress = mutableMapOf<String, BluetoothSocket>()
	private var _serverSocket: BluetoothServerSocket? = null


	override fun startScan(): Boolean {
		if (!canEnableBluetooth) {
			return false
		}

		val adapter = _bluetoothAdapter ?: return false

		updateDevices()

		return adapter.startDiscovery()
	}

	override fun stopScan(): Boolean {
		if (!canEnableBluetooth) {
			return false
		}
		val adapter = _bluetoothAdapter ?: return false

		return adapter.cancelDiscovery()
	}

	override suspend fun startBluetoothServer(): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")

		_isWaitingForConnection.value = true
		val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
			SdpRecordName,
			SdpRecordUuid,
		)
		_serverSocket = serverSocket

		val clientSocket = serverSocket.tryAccept()
		if (clientSocket == null) {
			_isWaitingForConnection.value = false
			serverSocket.close()
			_serverSocket = null
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		_isWaitingForConnection.value = false

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == clientSocket.remoteDevice.address) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Connected)
				}
				else device
			}
		}

		_clientSocketByAddress[clientSocket.remoteDevice.address] = clientSocket
		updateDevices()

		val connectedDevice = _devices.value.find { it.address == clientSocket.remoteDevice.address }
			?: throw IllegalStateException("Connected device not found in the list of devices")

		return BluetoothController.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	override fun stopBluetoothServer() {
		_serverSocket?.close()
		_serverSocket = null
	}

	override suspend fun connectToDevice(address: String): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == address) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Connecting)
				}
				else device
			}
		}

		val androidDevice = adapter.getRemoteDevice(address)

		val clientSocket = androidDevice.createRfcommSocketToServiceRecord(SdpRecordUuid)
		_clientSocketByAddress[address] = clientSocket

		stopScan()

		if (clientSocket == null) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val isConnectionSuccessFull = clientSocket.tryConnect()
		if (!isConnectionSuccessFull) {
			_devices.update { devices ->
				devices.map { device ->
					if (device.address == address) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == androidDevice.address) {
					device.copy(
						name = androidDevice.name ?: device.name,
						connectionState = BluetoothDevice.ConnectionState.Connected,
					)
				}
				else device
			}
		}

		updateDevices()

		val connectedDevice = _devices.value.find { it.address == androidDevice.address }
			?: throw IllegalStateException("Connected device not found in the list of devices")

		return BluetoothController.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	override suspend fun disconnectFromDevice(address: String): Boolean {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return false
		}

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == address) {
					device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnecting)
				}
				else device
			}
		}

		val clientSocket = _clientSocketByAddress[address]
			?: throw IllegalStateException("No client socket found for address: $address")

		if (clientSocket.isConnected) {
			try {
				clientSocket.close()
				_clientSocketByAddress.remove(address)
				_devices.update { devices ->
					devices.map { device ->
						if (device.address == address) {
							device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
						}
						else device
					}
				}
				updateDevices()
				return true
			}
			catch (e: IOException) {
				println("$$$ BluetoothControllerImpl.disconnectFromDevice() error closing socket: ${e.message}")
			}
		}
		else {
			println("$$$ BluetoothControllerImpl.disconnectFromDevice() socket is not connected")
		}

		return false
	}

	private suspend fun BluetoothServerSocket.tryAccept(): BluetoothSocket? {
		return withContext(Dispatchers.IO) {
			val clientSocket = try {
				// If device is not paired it will show a pop-up dialog to pair it
				accept()
			}
			catch (e: IOException) {
				null
			}

			if (clientSocket == null) {
				close()
				return@withContext null
			}

			_clientSocketByAddress[clientSocket.remoteDevice.address] = clientSocket

			return@withContext clientSocket
		}
	}

	private suspend fun BluetoothSocket.tryConnect(): Boolean {
		return withContext(Dispatchers.IO) {
			try {
				// If device is not paired it will show a pop-up dialog to pair it
				connect()
				return@withContext true
			}
			catch (e: IOException) {
				close()
				return@withContext false
			}
		}
	}

	override fun listenMessagesFrom(address: String): Flow<BluetoothMessage> {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}

		val clientSocket = _clientSocketByAddress[address]
			?: throw IllegalStateException("Can't listen for messages if there's no client socket")

		return clientSocket.listenForIncomingString().map { message ->
			BluetoothMessage(
				isFromLocalUser = false,
				content = message,
				senderName = clientSocket.remoteDevice.name ?: "",
				senderAddress = clientSocket.remoteDevice?.address ?: "",
			)
		}.onCompletion {
			clientSocket.close()
			_clientSocketByAddress.remove(address)
		}
	}

	override suspend fun trySendMessage(
		address: String,
		message: String,
	): BluetoothMessage? {
		if (!canEnableBluetooth) {
			return null
		}

		val clientSocket = _clientSocketByAddress[address]
			?: throw IllegalStateException("Can't send message if there's no client socket for address: $address")
		if (clientSocket.sendString(message)) {
			return BluetoothMessage(
				senderName = null,
				senderAddress = "",
				content = message,
				isFromLocalUser = true,
			)
		}

		return null
	}

	private fun updateDevices() {
		println("$$$ BluetoothControllerImpl.updateDevices(): $canEnableBluetooth, _bluetoothAdapter = $_bluetoothAdapter")
		if (!canEnableBluetooth) {
			return
		}
		val adapter = _bluetoothAdapter ?: return

		_devices.update { devices ->
			val pairedAndroidDevices = adapter.bondedDevices

			buildList {
				devices.forEach { device ->
					val androidDevice = pairedAndroidDevices.find { it.address == device.address } ?: return@forEach
					val updatedDevice = device.copy(
						pairingState = getPairingStateFromAndroidDevice(androidDevice),
					)
					add(updatedDevice)
				}

				val devicesAddresses = devices.map { it.address }
				pairedAndroidDevices.forEach { androidDevice ->
					if (androidDevice.address !in devicesAddresses) {
						val newDevice = BluetoothDevice(
							name = androidDevice.name,
							address = androidDevice.address,
							pairingState = getPairingStateFromAndroidDevice(androidDevice),
							connectionState = BluetoothDevice.ConnectionState.Disconnected,
						)
						add(newDevice)
					}
				}
			}
		}
	}

//	private fun BluetoothSocket.listenForIncomingData(): Flow<ByteArray> {
//		if (!isConnected) {
//			return emptyFlow()
//		}
//		return flow {
//			while (true) {
//				val buffer = ByteArray(32)
//				try {
//					inputStream.read(buffer)
//				}
//				catch (e: IOException) {
//					println("$$$ BluetoothControllerImpl.listenForIncomingData() IOException: ${e.message}")
//					break
//				}
//
//				println("$$$ BluetoothControllerImpl.listenForIncomingData() buffer = ${buffer.contentToString()}")
//				emit(buffer)
//			}
//		}.flowOn(Dispatchers.IO)
//	}

	private fun BluetoothSocket.listenForIncomingString(): Flow<String> {
		if (!isConnected) {
			return emptyFlow()
		}

		return channelFlow {
			while (isActive) {
				try {
					val result = inputStream.readString().also {
						println("$$$ stringOut = $it")
					}
					send(result)
				}
				catch (e: IOException) {
					println("$$$ BluetoothControllerImpl.listenForIncomingData() IOException: ${e.message}")
					this@channelFlow.close()
					break
				}
			}
		}.flowOn(Dispatchers.IO)
	}

	private suspend fun BluetoothSocket.sendString(string: String): Boolean {
		if (!isConnected) {
			return false
		}

		return withContext(Dispatchers.IO) {
			try {
				outputStream.write(string.toByteArray())

				return@withContext true
			}
			catch (e: IOException) {
				println("$$$ sendString error: ${e.message}")
				e.printStackTrace()
				return@withContext false
			}
		}
	}

	private fun getPairingStateFromAndroidDevice(androidDevice: AndroidBluetoothDevice): BluetoothDevice.PairingState {
		return when (androidDevice.bondState) {
			AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
			AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
			AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
			else -> BluetoothDevice.PairingState.None
		}
	}

	override fun onAppEnteredForeground() {
		updateDevices()
	}

	override fun onAppEnteredBackground() {
		// no-op
	}

	override fun onServiceRegistered() {
		registeredScope.launch {
			_bluetoothState.collect { state ->
				if (state == BluetoothController.BluetoothState.On) {
					updateDevices()
				}
			}
		}
		context.registerReceiver(
			_deviceFoundReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_FOUND),
		)
		context.registerReceiver(
			_discoveryStateChangeReceiver,
			IntentFilter().apply {
				addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
				addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
			},
		)
		context.registerReceiver(
			_bluetoothStateChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
		)
		context.registerReceiver(
			_bluetoothDeviceConnectionReceiver,
			IntentFilter().apply {
				addAction(AndroidBluetoothDevice.ACTION_ACL_CONNECTED)
				addAction(AndroidBluetoothDevice.ACTION_ACL_DISCONNECTED)
			},
		)
		context.registerReceiver(
			_bondStateChangeReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED),
		)
	}

	override fun onServiceUnregistered() {
		val adapter = _bluetoothAdapter ?: return

		println("$$$ BluetoothControllerImpl.onServiceUnregistered()")
		adapter.cancelDiscovery()
		context.unregisterReceiver(_deviceFoundReceiver)
		context.unregisterReceiver(_discoveryStateChangeReceiver)
		context.unregisterReceiver(_bluetoothStateChangeReceiver)
		context.unregisterReceiver(_bluetoothDeviceConnectionReceiver)
		context.unregisterReceiver(_bondStateChangeReceiver)

		_clientSocketByAddress.forEach { (_, socket) ->
			socket.close()
		}
		_clientSocketByAddress.clear()
	}

	companion object {
		private const val SdpRecordName = "chat_service"
		private val SdpRecordUuid = UUID.fromString("114799bb-c135-4cd0-aa22-459ba09d1e82")
	}
}

fun InputStream.readBytesFully(
	bufferSize: Int = 256,
): ByteArray {
	val output = ByteArrayOutputStream()
	val buffer = ByteArray(bufferSize)
	var bytesRead: Int
	do {
		bytesRead = read(buffer)
		if (bytesRead > 0) {
			output.write(buffer, 0, bytesRead)
		}
	}
	while (bytesRead != -1)
	return output.toByteArray()
}

fun InputStream.readString(
	bufferSize: Int = 256,
): String = buildString {
	val buffer = ByteArray(bufferSize)
	do {
		val bytesRead: Int = read(buffer)
		if (bytesRead <= 0) {
			break
		}
		val message = buffer.decodeToString(endIndex = bytesRead)
		append(message)
	}
	while (available() != 0)
}
