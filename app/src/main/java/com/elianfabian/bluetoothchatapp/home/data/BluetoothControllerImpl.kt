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
import com.zhuinden.simplestack.Bundleable
import com.zhuinden.simplestack.ScopedServices
import com.zhuinden.statebundle.StateBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
	ApplicationBackgroundStateChangeCallback,
	Bundleable {

	private val context: Context get() = mainActivityHolder.mainActivity

	private var _launcherKey = UUID.randomUUID().toString()

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

//	private val _scannedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
//	override val scannedDevices = _scannedDevices.asStateFlow()
//
//	private val _pairedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
//	override val pairedDevices = _pairedDevices.asStateFlow()

	private val _devices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val devices = _devices.asStateFlow()

	private val _connectionState = MutableStateFlow(BluetoothController.ConnectionState.Disconnected)
	override val connectionState = _connectionState.asStateFlow()

	private val _paringState = MutableStateFlow(BluetoothController.PairingState.None)
	override val pairingState = _paringState.asStateFlow()


	private val _deviceFoundReceiver = DeviceFoundBroadcastReceiver(
		onDeviceFound = { androidDevice ->
			println("$$$ BluetoothControllerImpl.onDeviceFound() androidDevice = $androidDevice")
			_devices.update { devices ->
				val existingDevice = devices.find { it.address == androidDevice.address }
				if (existingDevice != null) {
					// Update existing device
					devices.map { device ->
						if (device.address == androidDevice.address) {
							device.copy(
								name = androidDevice.name ?: device.name,
								pairingState = when (androidDevice.bondState) {
									AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
									AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
									AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
									else -> BluetoothDevice.PairingState.None
								},
							)
						}
						else device
					}
				}
				else {
					devices + BluetoothDevice(
						name = androidDevice.name,
						address = androidDevice.address,
						pairingState = when (androidDevice.bondState) {
							AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
							AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
							AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
							else -> BluetoothDevice.PairingState.None
						},
						isConnected = false,
					)
				}
			}

//			_scannedDevices.update { scannedDevices ->
//				if (device !in scannedDevices) {
//					scannedDevices + device
//				}
//				else scannedDevices
//			}
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
//			val adapter = _bluetoothAdapter ?: return@BluetoothDeviceConnectionBroadcastReceiver
//			if (device !in adapter.bondedDevices) {
//				return@BluetoothDeviceConnectionBroadcastReceiver
//			}
//			println("$$$ BluetoothControllerImpl.onConnectionStateChange() device(name=${device.name}, address=${device.address}), isConnected=$isConnected")
//			_connectionState.value = if (isConnected) {
//				BluetoothController.ConnectionState.Connected
//			}
//			else BluetoothController.ConnectionState.Disconnected

			println("$$$ BluetoothControllerImpl.onConnectionStateChange() androidDevice(name=${androidDevice.name}, address=${androidDevice.address}), isConnected=$isConnected")

			_devices.update { devices ->
				devices.map { device ->
					if (device.address == androidDevice.address) {
						device.copy(isConnected = isConnected)
					}
					else device
				}
			}
		}
	)

	private val _bondStateChangeReceiver = DeviceBondStateChangeBroadcastReceiver(
		onStateChange = { device, state ->
			println("$$$ BluetoothControllerImpl.onBondStateChange() device(name=${device.name}, address=${device.address}), state=$state")
			_devices.update { devices ->
				devices.map { existingDevice ->
					if (existingDevice.address == device.address) {
						existingDevice.copy(
							name = device.name ?: existingDevice.name,
							pairingState = when (state) {
								AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
								AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
								AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
								else -> BluetoothDevice.PairingState.None
							},
						)
					}
					else existingDevice
				}
			}
//			when (state) {
//				AndroidBluetoothDevice.BOND_BONDED -> {
//					_paringState.value = BluetoothController.PairingState.Paired
//				}
//				AndroidBluetoothDevice.BOND_BONDING -> {
//					_paringState.value = BluetoothController.PairingState.Pairing
//				}
//				AndroidBluetoothDevice.BOND_NONE -> {
//					_paringState.value = BluetoothController.PairingState.None
//				}
//			}
		}
	)

	private val _clientSocketByAddress = mutableMapOf<String, BluetoothSocket>()


	override fun startScan() {
		if (!canEnableBluetooth) {
			return
		}

		val adapter = _bluetoothAdapter ?: return

		updateDevices()

		adapter.startDiscovery()
	}

	override fun stopScan() {
		if (!canEnableBluetooth) {
			return
		}
		val adapter = _bluetoothAdapter ?: return

		adapter.cancelDiscovery()
	}

	override suspend fun startBluetoothServer(): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")

		// TODO: check if we truly need to update the connection state here or it's just enough to do it in the corresponding receiver
		_connectionState.value = BluetoothController.ConnectionState.Connecting

		val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
			SdpRecordName,
			SdpRecordUuid,
		)

		val clientSocket = serverSocket.tryAccept()
		if (clientSocket == null) {
			_connectionState.value = BluetoothController.ConnectionState.Disconnected
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == clientSocket.remoteDevice.address) {
					device.copy(isConnected = true)
				}
				else device
			}
		}

		_clientSocketByAddress[clientSocket.remoteDevice.address] = clientSocket
		_connectionState.value = BluetoothController.ConnectionState.Connected
		updateDevices()

		val connectedDevice = _devices.value.find { it.address == clientSocket.remoteDevice.address }
			?: throw IllegalStateException("Connected device not found in the list of devices")

		return BluetoothController.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	override suspend fun connectToDevice(address: String): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")
		_connectionState.value = BluetoothController.ConnectionState.Connecting

		val androidDevice = adapter.getRemoteDevice(address)

		val clientSocket = androidDevice.createRfcommSocketToServiceRecord(SdpRecordUuid)
//		_currentClientSocket = clientSocket
		_clientSocketByAddress[address] = clientSocket

		stopScan()

		if (clientSocket == null) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val isConnectionSuccessFull = clientSocket.tryConnect()
		if (!isConnectionSuccessFull) {
			_connectionState.value = BluetoothController.ConnectionState.Disconnected
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		_devices.update { devices ->
			devices.map { device ->
				if (device.address == androidDevice.address) {
					device.copy(
						name = androidDevice.name ?: device.name,
						isConnected = true,
					)
				}
				else device
			}
		}

		_connectionState.value = BluetoothController.ConnectionState.Connected
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

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")
		val androidDevice = adapter.getRemoteDevice(address)

//		val clientSocket = _currentClientSocket ?: return false
		val clientSocket = _clientSocketByAddress[address]
			?: throw IllegalStateException("No client socket found for address: $address")
//		if (clientSocket.isConnected && androidDevice.address == clientSocket.remoteDevice.address) {
//			clientSocket.close()
//			_currentClientSocket = null
//			_connectionState.value = BluetoothController.ConnectionState.Disconnected
//			updateDevices()
//			return true
//		}
		if (clientSocket.isConnected) {
			try {
				clientSocket.close()
				_clientSocketByAddress.remove(address)
				_connectionState.value = BluetoothController.ConnectionState.Disconnected
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
//			_currentClientSocket = clientSocket
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
				_connectionState.value = BluetoothController.ConnectionState.Connected
				return@withContext true
			}
			catch (e: IOException) {
				close()
				_connectionState.value = BluetoothController.ConnectionState.Disconnected
				return@withContext false
			}
		}
	}

	override fun listenMessagesFrom(address: String): Flow<BluetoothMessage> {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}

//		val clientSocket = _currentClientSocket ?: throw IllegalStateException("Can't listen for messages if there's no client socket")
		val clientSocket = _clientSocketByAddress[address]
			?: throw IllegalStateException("Can't listen for messages if there's no client socket")

		return clientSocket.listenForIncomingString().map { message ->
			BluetoothMessage(
				isFromLocalUser = false,
				content = message,
				senderName = "",
			)
		}
	}

	override suspend fun trySendMessage(
		address: String,
		message: String,
	): BluetoothMessage? {
		if (!canEnableBluetooth) {
			return null
		}

//		val clientSocket = _currentClientSocket ?: return null
		val clientSocket = _clientSocketByAddress[address]
			?: throw IllegalStateException("Can't send message if there's no client socket for address: $address")
		if (clientSocket.sendString(message)) {
			return BluetoothMessage(
				senderName = "",
				content = message,
				isFromLocalUser = true,
			)
		}

		return null
	}

	override fun closeConnection() {
//		_currentServerSocket?.close()
//		_currentClientSocket?.close()
//		_currentServerSocket = null
//		_currentClientSocket = null
		_clientSocketByAddress.forEach { (_, socket) ->
			try {
				socket.close()
			}
			catch (e: IOException) {
				println("$$$ BluetoothControllerImpl.closeConnection() error closing socket: ${e.message}")
			}
		}
		_clientSocketByAddress.clear()
		_connectionState.value = BluetoothController.ConnectionState.Disconnected
		updateDevices()
		println("$$$ BluetoothControllerImpl.closeConnection() called")
	}

	private fun updateDevices() {
		println("$$$ BluetoothControllerImpl.updateDevices(): $canEnableBluetooth, _bluetoothAdapter = $_bluetoothAdapter")
		if (!canEnableBluetooth) {
			return
		}
		val adapter = _bluetoothAdapter ?: return

		_devices.update { devices ->
			val pairedAndroidDevices = adapter.bondedDevices.also {
				println("$$$ pairedAndroidDevices = $it")
			}

			buildList {
				devices.forEach { device ->
					val androidDevice = pairedAndroidDevices.find { it.address == device.address }
					val updatedDevice = device.copy(
						pairingState = when (androidDevice?.bondState) {
							AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
							AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
							AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
							else -> BluetoothDevice.PairingState.None
						},
					)
					add(updatedDevice)
				}

				val devicesAddresses = devices.map { it.address }
				println("$$$ devicesAddresses = $devicesAddresses")
				pairedAndroidDevices.forEach { androidDevice ->
					println("$$$ BluetoothControllerImpl.updateDevices() androidDevice = ${androidDevice.name}, ${androidDevice.address}")
					if (androidDevice.address !in devicesAddresses) {
						val newDevice = BluetoothDevice(
							name = androidDevice.name,
							address = androidDevice.address,
							pairingState = when (androidDevice.bondState) {
								AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
								AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
								AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
								else -> BluetoothDevice.PairingState.None
							},
							isConnected = false,
						)
						println("$$$ BluetoothControllerImpl.updateDevices() adding new device: $newDevice")
						add(newDevice)
					}
				}
			}
		}
	}

	private fun BluetoothSocket.listenForIncomingData(): Flow<ByteArray> {
		if (!isConnected) {
			return emptyFlow()
		}
		return flow {
			while (true) {
				val buffer = ByteArray(32)
				try {
					inputStream.read(buffer)
				}
				catch (e: IOException) {
					println("$$$ BluetoothControllerImpl.listenForIncomingData() IOException: ${e.message}")
					break
				}

				println("$$$ BluetoothControllerImpl.listenForIncomingData() buffer = ${buffer.contentToString()}")
				emit(buffer)
			}
		}.flowOn(Dispatchers.IO)
	}

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
		}.onCompletion {
			closeConnection()
		}.flowOn(Dispatchers.IO)
	}

	private suspend fun BluetoothSocket.sendString(string: String): Boolean {
		if (!isConnected) {
			return false
		}

		println("$$$ sendString($string)")

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

	override fun onAppEnteredForeground() {
		updateDevices()
	}

	override fun onAppEnteredBackground() {
		// no-op$
	}

	override fun onServiceRegistered() {
		registeredScope.launch {
			_bluetoothState.collect { state ->
				println("$$$ BluetoothControllerImpl.onServiceRegistered() Bluetooth state changed to $state")
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
				addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) // I'm not sure this is needed
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
	}

	override fun toBundle(): StateBundle {
		return StateBundle().apply {
			putString("launcherKey", _launcherKey)
		}
	}

	override fun fromBundle(bundle: StateBundle?) {
		val stateBundle = bundle ?: return
		_launcherKey = stateBundle.getString("launcherKey") ?: UUID.randomUUID().toString()
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
