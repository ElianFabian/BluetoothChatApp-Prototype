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

	private val _scannedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val scannedDevices = _scannedDevices.asStateFlow()

	private val _pairedDevices = MutableStateFlow(emptyList<BluetoothDevice>())
	override val pairedDevices = _pairedDevices.asStateFlow()

	private val _connectionState = MutableStateFlow(BluetoothController.DeviceConnectionState.Disconnected)
	override val connectionState = _connectionState.asStateFlow()


	private val _foundDeviceReceiver = FoundDeviceBroadcastReceiver(
		onDeviceFound = { androidDevice ->
			val device = BluetoothDevice(
				name = androidDevice.name,
				address = androidDevice.address,
			)

			_scannedDevices.update { scannedDevices ->
				if (device !in scannedDevices) {
					scannedDevices + device
				}
				else scannedDevices
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
		onConnectionStateChange = { device, isConnected ->
			val adapter = _bluetoothAdapter ?: return@BluetoothDeviceConnectionBroadcastReceiver
			if (device !in adapter.bondedDevices) {
				return@BluetoothDeviceConnectionBroadcastReceiver
			}
			println("$$$ BluetoothControllerImpl.onConnectionStateChange() device(name=${device.name}, address=${device.address}), isConnected=$isConnected")
			_connectionState.value = if (isConnected) {
				BluetoothController.DeviceConnectionState.Connected
			}
			else BluetoothController.DeviceConnectionState.Disconnected
		}
	)

	private var _currentClientSocket: BluetoothSocket? = null


	override fun startScan() {
		if (!canEnableBluetooth) {
			return
		}

		val adapter = _bluetoothAdapter ?: return

		updatePairedDevices()

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

		_connectionState.value = BluetoothController.DeviceConnectionState.Connecting

		val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
			SdpRecordName,
			SdpRecordUuid,
		)

		val clientSocket = serverSocket.tryAccept()
		if (clientSocket == null) {
			_connectionState.value = BluetoothController.DeviceConnectionState.Disconnected
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		_currentClientSocket = clientSocket
		_connectionState.value = BluetoothController.DeviceConnectionState.Connected
		updatePairedDevices()

		return BluetoothController.ConnectionResult.ConnectionEstablished
	}

	override suspend fun connectToDevice(device: BluetoothDevice): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")
		_connectionState.value = BluetoothController.DeviceConnectionState.Connecting

		val androidDevice = adapter.getRemoteDevice(device.address)

		val clientSocket = androidDevice.createRfcommSocketToServiceRecord(SdpRecordUuid)
		_currentClientSocket = clientSocket

		stopScan()

		if (clientSocket == null) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val isConnectionSuccessFull = clientSocket.tryConnect()
		if (!isConnectionSuccessFull) {
			_connectionState.value = BluetoothController.DeviceConnectionState.Disconnected
			return BluetoothController.ConnectionResult.CouldNotConnect
		}
		_connectionState.value = BluetoothController.DeviceConnectionState.Connected
		updatePairedDevices()
		return BluetoothController.ConnectionResult.ConnectionEstablished
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
			_currentClientSocket = clientSocket
			if (clientSocket == null) {
				close()
				return@withContext null
			}


			return@withContext clientSocket
		}
	}

	private suspend fun BluetoothSocket.tryConnect(): Boolean {
		return withContext(Dispatchers.IO) {
			try {
				// If device is not paired it will show a pop-up dialog to pair it
				connect()
				_connectionState.value = BluetoothController.DeviceConnectionState.Connected
				return@withContext true
			}
			catch (e: IOException) {
				close()
				_connectionState.value = BluetoothController.DeviceConnectionState.Disconnected
				return@withContext false
			}
		}
	}

	override fun listenMessages(): Flow<BluetoothMessage> {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}

		val clientSocket = _currentClientSocket ?: throw IllegalStateException("Can't listen for messages if there's no client socket")

		return clientSocket.listenForIncomingString().map { message ->
			BluetoothMessage(
				isFromLocalUser = false,
				content = message,
				senderName = "",
			)
		}
	}

	override suspend fun trySendMessage(message: String): BluetoothMessage? {
		if (!canEnableBluetooth) {
			return null
		}

		val clientSocket = _currentClientSocket ?: return null
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
		_currentClientSocket?.close()
//		_currentServerSocket = null
		_currentClientSocket = null
	}

	private fun updatePairedDevices() {
		if (!canEnableBluetooth) {
			return
		}
		val adapter = _bluetoothAdapter ?: return

		_pairedDevices.value = adapter
			.bondedDevices
			.map { device ->
				BluetoothDevice(
					name = device.name,
					address = device.address,
				)
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
		updatePairedDevices()
	}

	override fun onAppEnteredBackground() {
		// no-op$
	}

	override fun onServiceRegistered() {
		registeredScope.launch {
			_bluetoothState.collect { state ->
				if (state == BluetoothController.BluetoothState.On) {
					updatePairedDevices()
				}
			}
		}
		context.registerReceiver(
			_foundDeviceReceiver,
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
		BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
	}

	override fun onServiceUnregistered() {
		val adapter = _bluetoothAdapter ?: return

		println("$$$ BluetoothControllerImpl.onServiceUnregistered()")
		adapter.cancelDiscovery()
		context.unregisterReceiver(_foundDeviceReceiver)
		context.unregisterReceiver(_discoveryStateChangeReceiver)
		context.unregisterReceiver(_bluetoothStateChangeReceiver)
		context.unregisterReceiver(_bluetoothDeviceConnectionReceiver)
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

		private object DataType {
			const val String = 1
		}
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
