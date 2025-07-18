package com.elianfabian.bluetoothchatapp_prototype.common.data

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
import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver.BluetoothDeviceConnectionBroadcastReceiver
import com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver.BluetoothDeviceNameChangeBroadcastReceiver
import com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver.BluetoothDiscoveryStateChangeBroadcastReceiver
import com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver.BluetoothStateChangeBroadcastReceiver
import com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver.DeviceBondStateChangeBroadcastReceiver
import com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver.DeviceFoundBroadcastReceiver
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp_prototype.common.domain.BluetoothController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.BluetoothDevice
import com.elianfabian.bluetoothchatapp_prototype.common.domain.MultiplePermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.PermissionState
import com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.callbacks.ApplicationBackgroundStateChangeCallback
import com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.callbacks.OnCreateApplicationCallback
import com.elianfabian.bluetoothchatapp_prototype.home.data.AndroidBluetoothDevice
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BluetoothControllerImpl(
	private val context: Context,
	private val applicationScope: CoroutineScope,
	private val bluetoothPermissionController: MultiplePermissionController,
	private val androidHelper: AndroidHelper,
) : BluetoothController,
	ScopedServices.Registered,
	OnCreateApplicationCallback,
	ApplicationBackgroundStateChangeCallback {


	override fun onCreateApplication() {
		applicationScope.launch {
			_bluetoothState.collect { state ->
				if (state == BluetoothController.BluetoothState.Off) {
					_serverSocket?.close()
					_serverSocket = null

					_clientSocketByAddress.forEach { (_, socket) ->
						socket.close()
					}
					_clientSocketByAddress.clear()

					_devices.update { devices ->
						devices.map { device ->
							val disconnectedDevice = device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)

							if (device.connectionState == BluetoothDevice.ConnectionState.Connected) {
								applicationScope.launch {
									_events.emit(
										BluetoothController.Event.OnDeviceDisconnected(
											disconnectedDevice = disconnectedDevice,
											manuallyDisconnected = true,
										)
									)
								}
							}

							disconnectedDevice
						}
					}
				}
			}
		}
		applicationScope.launch {
			combineTuple(
				_bluetoothState,
				bluetoothPermissionController.state,
			).collect { (bluetoothState, permissionsState) ->
				if (bluetoothState.isOn && permissionsState.values.all { it == PermissionState.Granted }) {
					updateDevices()
					_bluetoothDeviceName.value = _bluetoothAdapter?.name
				}
			}
		}
		context.registerReceiver(
			_bluetoothDeviceNameChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED),
		)
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

	override fun onServiceRegistered() {
	}

	override fun onServiceUnregistered() {
		// Ignore since this will be used in a Foreground service
//		val adapter = _bluetoothAdapter ?: return
//
//		adapter.cancelDiscovery()
//		context.unregisterReceiver(_bluetoothDeviceNameChangeReceiver)
//		context.unregisterReceiver(_deviceFoundReceiver)
//		context.unregisterReceiver(_discoveryStateChangeReceiver)
//		context.unregisterReceiver(_bluetoothStateChangeReceiver)
//		context.unregisterReceiver(_bluetoothDeviceConnectionReceiver)
//		context.unregisterReceiver(_bondStateChangeReceiver)
//
//		_clientSocketByAddress.forEach { (_, socket) ->
//			socket.close()
//		}
//		_clientSocketByAddress.clear()
//		_serverSocket?.close()
//		_serverSocket = null
	}

	private val _bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: throw IllegalStateException("Couldn't get the BluetoothManager")
	private val _bluetoothAdapter: BluetoothAdapter? = _bluetoothManager.adapter

	private val _bluetoothDeviceName = MutableStateFlow(
		if (canEnableBluetooth) {
			_bluetoothAdapter?.name
		}
		else null
	)
	override val bluetoothDeviceName = _bluetoothDeviceName.asStateFlow()

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

	private val _events = MutableSharedFlow<BluetoothController.Event>()
	override val events = _events.asSharedFlow()

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

			applicationScope.launch {
				println("$$$$$ BluetoothDeviceConnectionBroadcastReceiver androidDevice: $androidDevice, isConnected: $isConnected")
				if (!isConnected) {
					val clientSocket = _clientSocketByAddress.remove(androidDevice.address)
					val wasConnected = clientSocket?.isConnected == true
					println("$$$$$ clientSocket.isConnected: ${clientSocket?.isConnected}")

					clientSocket?.close()
					_clientJobByAddress[androidDevice.address]?.cancel()
					_clientJobByAddress.remove(androidDevice.address)
					_clientSharedFlowByAddress.remove(androidDevice.address)

					// FIX: Check if it was previously connected to avoid emitting the event when we tried to connect but it failed
					if (wasConnected) {
						_events.emit(
							BluetoothController.Event.OnDeviceDisconnected(
								disconnectedDevice = _devices.value.find { it.address == androidDevice.address } ?: run {
									println("$$$ not found: $androidDevice")
									return@launch
								},
								manuallyDisconnected = false,
							)
						)
					}

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

	private val _bluetoothDeviceNameChangeReceiver = BluetoothDeviceNameChangeBroadcastReceiver(
		onNameChange = { newName ->
			_bluetoothDeviceName.value = newName
		}
	)

	private var _serverSocket: BluetoothServerSocket? = null
	private val _clientSocketByAddress: MutableMap<String, BluetoothSocket> = ConcurrentHashMap()
	private val _clientJobByAddress: MutableMap<String, Job> = ConcurrentHashMap()
	private val _clientSharedFlowByAddress: MutableMap<String, MutableSharedFlow<BluetoothMessage>> = ConcurrentHashMap()

	private val _loadingClients = MutableStateFlow<List<BluetoothController.LoadingClient>>(emptyList())
	override val loadingClients = _loadingClients.asStateFlow()


	override fun setBluetoothDeviceName(name: String): Boolean {
		if (!canEnableBluetooth) {
			return false
		}
		val adapter = _bluetoothAdapter ?: return false

		// Not all devices support changing the Bluetooth name, and there doesn't seem to be a way to check it
		// Here's a list of devices that I tested that support it:
		// - Google
		// - Motorola
		// - Sony Xperia 10 (34)
		// - SHARP AQUOS
		// - ZTE
		// - FUJITSU
		//
		// And here's a list of devices that I tested that don't support it:
		// - Huawei
		// - Xiaomi
		// - Realme
		// - Samsung
		//
		// Notes:
		// - Bluetooth must be enabled to change the name
		// - Immediately calling BluetoothAdapter.getName() after calling BluetoothAdapter.setName(...) won't return the new name
		return adapter.setName(name)
	}

	override fun startScan(): Boolean {
		if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 31) {
			return false
		}

		val adapter = _bluetoothAdapter ?: return false

		updateDevices()

		return adapter.startDiscovery()
	}

	override fun stopScan(): Boolean {
		if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 31) {
			return false
		}

		val adapter = _bluetoothAdapter ?: return false

		return adapter.cancelDiscovery()
	}

	override suspend fun startBluetoothServer(): BluetoothController.ConnectionResult {
		return startBluetoothServerInternal()
	}

	override suspend fun startInsecureBluetoothServer(): BluetoothController.ConnectionResult {
		return startBluetoothServerInternal(insecure = true)
	}

	// Both server and the device who connects have to do it insecurely to avoid the need of linking.
	private suspend fun startBluetoothServerInternal(insecure: Boolean = false): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		val adapter = _bluetoothAdapter ?: throw NullPointerException("Bluetooth adapter is null")

		println("$$$$$ clients = $_clientSocketByAddress")

		_serverSocket?.close()
		_serverSocket = null

		_isWaitingForConnection.value = true

		val serverSocket = if (insecure) {
			adapter.listenUsingInsecureRfcommWithServiceRecord(
				SdpRecordName,
				SdpRecordUuid,
			)
		}
		else {
			adapter.listenUsingRfcommWithServiceRecord(
				SdpRecordName,
				SdpRecordUuid,
			)
		}
		_serverSocket = serverSocket

		val clientSocket = serverSocket.tryAccept()

		serverSocket.close()
		_serverSocket = null

		if (clientSocket == null) {
			_isWaitingForConnection.value = false
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		_isWaitingForConnection.value = false
		stopScan()

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket

		val connectedDevice = BluetoothDevice(
			name = connectedAndroidDevice.name,
			address = connectedAndroidDevice.address,
			pairingState = getPairingStateFromAndroidDevice(connectedAndroidDevice),
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		println("$$$ onDeviceConnected: 1")
		_events.emit(
			BluetoothController.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = false,
			)
		)

		_devices.update { devices ->
			val androidBondedDevices = adapter.bondedDevices
			val isDeviceInList = devices.any { it.address == connectedDevice.address }
			if (isDeviceInList) {
				devices.map { device ->
					if (device.address == connectedDevice.address) {
						val isPaired = androidBondedDevices.find { it.address == device.address } != null
						device.copy(
							connectionState = BluetoothDevice.ConnectionState.Connected,
							pairingState = if (isPaired) {
								BluetoothDevice.PairingState.Paired
							}
							else device.pairingState,
						)
					}
					else device
				}
			}
			else devices + connectedDevice
		}

		updateDevices()

		return BluetoothController.ConnectionResult.ConnectionEstablished(connectedDevice)
	}

	override fun stopBluetoothServer() {
		_serverSocket?.close()
		_serverSocket = null
	}

	override suspend fun connectToDevice(address: String): BluetoothController.ConnectionResult {
		return connectToDeviceInternal(address)
	}

	override suspend fun connectToDeviceInsecurely(address: String): BluetoothController.ConnectionResult {
		return connectToDeviceInternal(address, insecure = true)
	}

	// Both server and the device who connects have to do it insecurely to avoid the need of linking.
	private suspend fun connectToDeviceInternal(address: String, insecure: Boolean = false): BluetoothController.ConnectionResult {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		println("$$$$$ connectToDevice insecure: $insecure")

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

		val clientSocket = if (insecure) {
			androidDevice.createInsecureRfcommSocketToServiceRecord(SdpRecordUuid)
		}
		else androidDevice.createRfcommSocketToServiceRecord(SdpRecordUuid)

		val connectedAndroidDevice = clientSocket.remoteDevice
		_clientSocketByAddress[connectedAndroidDevice.address] = clientSocket

		if (clientSocket == null) {
			return BluetoothController.ConnectionResult.CouldNotConnect
		}

		stopScan()

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

		val connectedDevice = BluetoothDevice(
			name = connectedAndroidDevice.name,
			address = connectedAndroidDevice.address,
			pairingState = getPairingStateFromAndroidDevice(connectedAndroidDevice),
			connectionState = BluetoothDevice.ConnectionState.Connected,
		)

		println("$$$ onDeviceConnected: 2")
		_events.emit(
			BluetoothController.Event.OnDeviceConnected(
				connectedDevice = connectedDevice,
				manuallyConnected = true,
			)
		)

		val newDevices = _devices.updateAndGet { devices ->
			val androidBondedDevices = adapter.bondedDevices
			val isDeviceInList = devices.any { it.address == connectedDevice.address }
			if (isDeviceInList) {
				devices.map { device ->
					if (device.address == connectedDevice.address) {
						val isPaired = androidBondedDevices.find { it.address == device.address } != null
						device.copy(
							connectionState = BluetoothDevice.ConnectionState.Connected,
							pairingState = if (isPaired) {
								BluetoothDevice.PairingState.Paired
							}
							else device.pairingState,
						)
					}
					else device
				}
			}
			else devices + connectedDevice
		}

		println("$$$ connectToDeviceInternal: ${newDevices.filter { it.address == address }}")

		updateDevices()

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

		// If the clientSocket is null it should mean it was already disconnected
		val clientSocket = _clientSocketByAddress[address] ?: return false
		if (!clientSocket.isConnected) {
			return false
		}

		val manuallyDisconnected = try {
			println("$$$$$ start send empty byte array")
			clientSocket.outputStream.write(byteArrayOf())
			println("$$$$$ end send empty byte array")
			true
		}
		catch (e: IOException) {
			if (e.message.orEmpty().contains("Broken pipe")) {
				false
			}
			else throw e
		}

		println("$$$$$ disconnectFromDevice isConnected: ${clientSocket.isConnected}")
		try {
			clientSocket.close()
			_clientSocketByAddress.remove(address)
			_clientJobByAddress[address]?.cancel()
			_clientJobByAddress.remove(address)
			_clientSharedFlowByAddress.remove(address)

			_events.emit(
				BluetoothController.Event.OnDeviceDisconnected(
					disconnectedDevice = _devices.value.first { it.address == address },
					manuallyDisconnected = manuallyDisconnected,
				)
			)

			_devices.update { devices ->
				devices.map { device ->
					if (device.address == address) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
			updateDevices()
			println("$$$$$ disconnectFromDevice effectively disconnected: $clientSocket")
			return true
		}
		catch (e: IOException) {
			println("$$$$$ disconnectFromDevice() error closing socket: ${e.message}")
		}

		return false
	}

	override suspend fun cancelConnectionAttempt(address: String): Boolean {
		if (!canEnableBluetooth) {
			throw SecurityException("BLUETOOTH_CONNECT permission was not granted.")
		}
		if (!_bluetoothState.value.isOn) {
			return false
		}

		val clientSocket = _clientSocketByAddress[address] ?: return false

		if (clientSocket.isConnected) {
			return false
		}

		try {
			clientSocket.close()
			_clientSocketByAddress.remove(address)
			_clientJobByAddress[address]?.cancel()
			_clientJobByAddress.remove(address)
			_clientSharedFlowByAddress.remove(address)

			_devices.update { devices ->
				devices.map { device ->
					if (device.address == address) {
						device.copy(connectionState = BluetoothDevice.ConnectionState.Disconnected)
					}
					else device
				}
			}
		}
		catch (e: IOException) {
			println("$$$ cancelConnectionAttempt error: $e")
			return false
		}

		return true
	}

	private suspend fun BluetoothServerSocket.tryAccept(): BluetoothSocket? {
		return withContext(Dispatchers.IO) {
			val clientSocket = try {
				// If device is not paired it will show a pop-up dialog to pair it
				println("$$$$$ tryAccept")
				accept()
			}
			catch (e: IOException) {
				println("$$$$$ tryAccept error: $e")
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
				// If device is not paired it will show a pop-up dialog to pair it (if the connection is done securely)
				println("$$$$$ tryConnect")
				connect()
				return@withContext true
			}
			catch (e: IOException) {
				println("$$$$$ tryConnect error: $e")
				// This message can happen when you try to connect to a device that is not acting as a server (and probably in more cases),
				// but also sometimes it just throws the error when you try to connect, because of this
				// we try to connect again.
				if (e.message.orEmpty().contains("read failed, socket might closed or timeout")) {
					try {
						connect()
						return@withContext true
					}
					catch (e: IOException) {
						println("$$$ inner error: $e")
					}
				}
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

		val sharedFlow = _clientSharedFlowByAddress.getOrPut(address) {
			MutableSharedFlow()
		}

		_clientJobByAddress[address] = applicationScope.launch {
			clientSocket.listenForIncomingString().collect { message ->
				val bluetoothMessage = BluetoothMessage(
					isFromLocalUser = false,
					content = message,
					senderName = clientSocket.remoteDevice.name ?: "",
					senderAddress = clientSocket.remoteDevice?.address ?: "",
					isRead = !androidHelper.isAppClosed() && !androidHelper.isAppInBackground(),
				)

				println("$$$ bluetoothMessage = $bluetoothMessage")

				sharedFlow.emit(bluetoothMessage)
			}
		}

		return sharedFlow
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
				isRead = !androidHelper.isAppClosed() && !androidHelper.isAppInBackground(),
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

		val newDevices = _devices.updateAndGet { devices ->
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

				val notPairedDevices = devices.filter {
					it.pairingState != BluetoothDevice.PairingState.Paired
				}
				addAll(notPairedDevices)
			}
		}

		println("$$$ updateDevices: ${newDevices.filter { it.address == "BC:2D:EF:43:AC:0C" }}")
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
					val result = inputStream.readString(
						onRead = { progress ->
							_loadingClients.update { clients ->
								val client = clients.find { it.address == remoteDevice.address }
								if (client != null) {
									clients.map { client ->
										if (client.address == remoteDevice.address) {
											BluetoothController.LoadingClient(
												name = remoteDevice.name,
												address = remoteDevice.address,
												progress = progress,
											)
										}
										else client
									}
								}
								else clients + BluetoothController.LoadingClient(
									name = remoteDevice.name,
									address = remoteDevice.address,
									progress = progress,
								)
							}
						}
					).also {
						println("$$$ stringOut = $it")
					}
					send(result)
				}
				catch (e: IOException) {
					println("$$$ BluetoothControllerImpl.listenForIncomingString() IOException: ${e.message}")
					this@channelFlow.close()
					break
				}
				finally {
					_loadingClients.update { clients ->
						clients.filter { it.address != remoteDevice.address }
					}
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
				val messageBytes = string.toByteArray(Charsets.UTF_8)
				val lengthPrefix = ByteBuffer
					.allocate(4)
					.order(ByteOrder.BIG_ENDIAN)
					.putInt(messageBytes.size)
					.array()

				outputStream.write(lengthPrefix)
				outputStream.write(messageBytes)
				outputStream.flush()

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

		if (canEnableBluetooth) {
			_bluetoothDeviceName.value = _bluetoothAdapter?.name
		}
	}

	override fun onAppEnteredBackground() {
		// no-op
	}

	companion object {
		private const val SdpRecordName = "chat_service"
		private val SdpRecordUuid = UUID.fromString("114799bb-c135-4cd0-aa22-459ba09d1e82")
	}
}

fun InputStream.readString(
	onRead: (progress: Float) -> Unit = {},
): String {
	val lengthBytes = ByteArray(4)
	readFully(lengthBytes)

	val length = ByteBuffer.wrap(lengthBytes)
		.order(ByteOrder.BIG_ENDIAN)
		.getInt()

	val buffer = ByteArray(length)
	onRead(0F)
	readFully(buffer) { bytesRead ->
		onRead(bytesRead.toFloat() / length)
	}

	return buffer.decodeToString()
}

private inline fun InputStream.readFully(
	buffer: ByteArray,
	onProgress: (bytesRead: Int) -> Unit = {},
) {
	var bytesRead = 0
	while (bytesRead < buffer.size) {
		val read = read(buffer, bytesRead, buffer.size - bytesRead)
		if (read == -1) {
			throw EOFException("Stream ended prematurely")
		}
		bytesRead += read
		onProgress(bytesRead)
	}
}
