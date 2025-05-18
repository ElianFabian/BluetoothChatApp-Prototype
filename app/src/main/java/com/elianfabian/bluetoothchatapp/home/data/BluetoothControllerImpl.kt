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
import com.elianfabian.bluetoothchatapp.chat.data.toBluetoothMessage
import com.elianfabian.bluetoothchatapp.chat.data.toByteArray
import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp.common.data.MainActivityHolder
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothDevice
import com.zhuinden.simplestack.Bundleable
import com.zhuinden.simplestack.ScopedServices
import com.zhuinden.statebundle.StateBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothControllerImpl(
	private val mainActivityHolder: MainActivityHolder,
) : BluetoothController,
	ScopedServices.Registered,
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

	private var _currentServerSocket: BluetoothServerSocket? = null
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

	override fun startBluetoothServer(): Flow<BluetoothController.ConnectionResult> {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		return flow {
			val adapter = _bluetoothAdapter ?: return@flow

			_connectionState.value = BluetoothController.DeviceConnectionState.Connecting

			val serverSocket = adapter.listenUsingRfcommWithServiceRecord(
				SdpRecordName,
				SdpRecordUuid,
			)
			_currentServerSocket = serverSocket

			var shouldLoop = true
			while (shouldLoop) {
				val clientSocket = try {
					serverSocket.accept()
				}
				catch (e: IOException) {
					shouldLoop = false
					null
				}
				_currentClientSocket = clientSocket
				if (clientSocket != null) {
					emit(BluetoothController.ConnectionResult.ConnectionEstablished)
					_connectionState.value = BluetoothController.DeviceConnectionState.Connected
					serverSocket.close()

					clientSocket.listenForIncomingData().collect { data ->
						println("$$$ serverData = ${data.contentToString()}")
						if (data.isEmpty()) {
							return@collect
						}
						val message = data.toBluetoothMessage(isFromLocalUser = false)
						emit(
							BluetoothController.ConnectionResult.Message(
								message = message,
							)
						)
					}
				}
			}
		}.onCompletion {
			println("$$$ BluetoothControllerImpl.startBluetoothServer() onCompletion")
			closeConnection()
		}.flowOn(Dispatchers.IO)
	}

	override fun connectToDevice(device: BluetoothDevice): Flow<BluetoothController.ConnectionResult> {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		return flow {
			val adapter = _bluetoothAdapter ?: return@flow
			_connectionState.value = BluetoothController.DeviceConnectionState.Connecting

			val androidDevice = adapter.getRemoteDevice(device.address)
			if (androidDevice !in adapter.bondedDevices) {
				emit(BluetoothController.ConnectionResult.DeviceIsNotPaired)
			}

			val clientSocket = androidDevice.createRfcommSocketToServiceRecord(SdpRecordUuid)
			_currentClientSocket = clientSocket

			stopScan()

			if (clientSocket != null) {
				try {
					clientSocket.connect()
					emit(BluetoothController.ConnectionResult.ConnectionEstablished)
					_connectionState.value = BluetoothController.DeviceConnectionState.Connected

					clientSocket.listenForIncomingData().collect { data ->
						println("$$$ clientData = ${data.contentToString()}")
						if (data.isEmpty()) {
							return@collect
						}
						val message = data.toBluetoothMessage(isFromLocalUser = false)
						emit(
							BluetoothController.ConnectionResult.Message(
								message = message,
							)
						)
					}
				}
				catch (e: IOException) {
					clientSocket.close()
					emit(BluetoothController.ConnectionResult.CouldNotConnect)
					_connectionState.value = BluetoothController.DeviceConnectionState.Disconnected
				}
			}
		}.onCompletion {
			println("$$$ BluetoothControllerImpl.connectToDevice() onCompletion")
			closeConnection()
		}.flowOn(Dispatchers.IO)
	}

	override suspend fun trySendMessage(message: String): BluetoothMessage? {
		if (!canEnableBluetooth) {
			return null
		}

		val adapter = _bluetoothAdapter ?: return null

		val bluetoothMessage = BluetoothMessage(
			content = message,
			senderName = adapter.name ?: "",
			isFromLocalUser = true,
		)

		val data = bluetoothMessage.toByteArray()
		val clientSocket = _currentClientSocket ?: return null

		if (!clientSocket.sendData(data)) {
			return null
		}
		return bluetoothMessage
	}

	override fun closeConnection() {
		_currentServerSocket?.close()
		_currentClientSocket?.close()
		_currentServerSocket = null
		_currentClientSocket = null
	}

	override fun release() {
		context.unregisterReceiver(_foundDeviceReceiver)
		context.unregisterReceiver(_bluetoothDeviceConnectionReceiver)
		closeConnection()
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
			val buffer = ByteArray(1024)
			while (true) {
				try {
					inputStream.read(buffer)
				}
				catch (e: IOException) {
					break
				}

				emit(buffer)
			}
		}.flowOn(Dispatchers.IO)
	}

	private suspend fun BluetoothSocket.sendData(data: ByteArray): Boolean {
		if (!isConnected) {
			return false
		}
		return withContext(Dispatchers.IO) {
			try {
				outputStream.write(data)
			}
			catch (e: IOException) {
				e.printStackTrace()
				return@withContext false
			}

			return@withContext true
		}
	}


	override fun onServiceRegistered() {
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
	}
}
