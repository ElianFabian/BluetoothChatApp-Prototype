package com.elianfabian.bluetoothchatapp.home.presentation

import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp.common.data.BluetoothPermissionController
import com.elianfabian.bluetoothchatapp.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp.common.domain.PermissionState
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothDevice
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.skip
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
	private val bluetoothController: BluetoothController,
	private val bluetoothPermissionController: BluetoothPermissionController,
	private val androidHelper: AndroidHelper,
	private val registeredScope: CoroutineScope,
) : ScopedServices.Registered {

	private val _permissionDialog = MutableStateFlow<HomeState.PermissionDialogState?>(null)
	private val _messages = MutableStateFlow<List<BluetoothMessage>>(emptyList())
	private val _enteredMessage = MutableStateFlow("")
	private val _targetDeviceAddress = MutableStateFlow<String?>(null)

	val state = combineTuple(
		bluetoothController.devices,
		bluetoothController.isScanning,
		bluetoothController.state,
		bluetoothPermissionController.state,
		_permissionDialog,
		bluetoothController.bluetoothDeviceName,
		_messages,
		_enteredMessage,
		_targetDeviceAddress,
		bluetoothController.isWaitingForConnection,
	).map { (devices, isScanning, bluetoothState, state, permissionDialog, bluetoothName, messages, enteredMessage, targetDeviceAddress, isWaitingForConnection) ->
		HomeState(
			pairedDevices = devices.filter {
				it.pairingState == BluetoothDevice.PairingState.Paired
			}.sortedByDescending {
				it.connectionState == BluetoothDevice.ConnectionState.Connected
			},
			scannedDevices = devices.filter {
				it.pairingState != BluetoothDevice.PairingState.Paired
					&& it.connectionState != BluetoothDevice.ConnectionState.Connected
			},
			isScanning = isScanning,
			isBluetoothSupported = bluetoothController.isBluetoothSupported,
			isBluetoothOn = bluetoothState.isOn,
			permissionState = state.values.toList(),
			permissionDialog = permissionDialog,
			bluetoothDeviceName = bluetoothName,
			messages = messages,
			enteredMessage = enteredMessage,
			targetDeviceAddress = targetDeviceAddress,
			isWaitingForConnection = isWaitingForConnection,
		)
	}.stateIn(
		scope = registeredScope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = HomeState(
			isBluetoothSupported = bluetoothController.isBluetoothSupported,
			isBluetoothOn = bluetoothController.state.value.isOn,
			permissionState = bluetoothPermissionController.state.value.values.toList(),
		),
	)

	fun sendAction(action: HomeAction) {
		when (action) {
			is HomeAction.StartScan -> {
				registeredScope.launch {
					executeIfBluetoothRequirementsAreSatisfied {
						if (!bluetoothController.startScan()) {
							// In some devices, at least for API level 29, if this returns false we likely
							// need to turn on location
							if (androidHelper.showEnableLocationDialog()) {
								bluetoothController.startScan()
							}
							else {
								androidHelper.showToast("Location is needed for scan to work")
							}
						}
					}
				}
			}
			is HomeAction.StopScan -> {
				bluetoothController.stopScan()
			}
			is HomeAction.StartServer -> {
				registeredScope.launch {
					executeIfBluetoothRequirementsAreSatisfied {
						val result = bluetoothController.startBluetoothServer()
						when (result) {
							is BluetoothController.ConnectionResult.ConnectionEstablished -> {
								_targetDeviceAddress.value = result.device.address
								bluetoothController.listenMessagesFrom(result.device.address).collect { message ->
									_messages.update {
										it + message
									}
								}
							}
							else -> Unit
						}
					}
				}
			}
			is HomeAction.StopServer -> {
				bluetoothController.stopBluetoothServer()
			}
			is HomeAction.OpenBluetoothSettings -> {
				androidHelper.openBluetoothSettings()
			}
			is HomeAction.OpenDeviceInfoSettings -> {
				androidHelper.openDeviceInfoSettings()
			}
			is HomeAction.MakeDeviceDiscoverable -> {
				registeredScope.launch {
					androidHelper.showMakeDeviceDiscoverableDialog()
				}
			}
			is HomeAction.SendMessage -> {
				val targetDeviceAddress = _targetDeviceAddress.value
				if (targetDeviceAddress == null) {
					androidHelper.showToast("Please, select a connected device to mark it as target.")
					return
				}
				if (_enteredMessage.value.isBlank()) {
					androidHelper.showToast("Please, enter a message to send.")
					return
				}
				val connectedDevices = bluetoothController.devices.value.filter {
					it.connectionState == BluetoothDevice.ConnectionState.Connected
				}
				if (connectedDevices.isEmpty()) {
					androidHelper.showToast("Please, connect to a device before sending a message.")
					return
				}
				if (connectedDevices.none { it.address == targetDeviceAddress }) {
					androidHelper.showToast("Please, connect to the device with address: $targetDeviceAddress before sending a message.")
					return
				}
				registeredScope.launch {
					val message = bluetoothController.trySendMessage(
						address = targetDeviceAddress,
						message = _enteredMessage.value,
					)
					if (message != null) {
						_messages.update {
							it + message
						}
						_enteredMessage.value = ""
						androidHelper.closeKeyboard()
					}
				}
			}
			is HomeAction.EnterMessage -> {
				_enteredMessage.value = action.message
			}
			is HomeAction.ClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_targetDeviceAddress.value = action.device.address
					return
				}
				registeredScope.launch {
					val result = bluetoothController.connectToDevice(action.device.address)
					when (result) {
						is BluetoothController.ConnectionResult.ConnectionEstablished -> {
							_targetDeviceAddress.value = result.device.address
							bluetoothController.listenMessagesFrom(result.device.address).collect { message ->
								_messages.update {
									it + message
								}
							}
						}
						else -> Unit
					}
				}
			}
			is HomeAction.ClickScannedDevice -> {
				registeredScope.launch {
					val result = bluetoothController.connectToDevice(action.device.address)
					when (result) {
						is BluetoothController.ConnectionResult.ConnectionEstablished -> {
							bluetoothController.listenMessagesFrom(result.device.address).collect { message ->
								_messages.update {
									it + message
								}
							}
						}
						else -> Unit
					}
				}
			}
			is HomeAction.LongClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					registeredScope.launch {
						if (bluetoothController.disconnectFromDevice(action.device.address)) {
//							androidHelper.showToast("Disconnected from: ${action.device.name}")
						}
						else {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is HomeAction.ClickMessage -> {
				if (!action.message.isFromLocalUser) {
					_targetDeviceAddress.value = action.message.senderAddress
				}
			}
		}
	}

	private suspend fun executeIfBluetoothRequirementsAreSatisfied(action: suspend () -> Unit) {
		bluetoothPermissionController.request()
		val result = bluetoothPermissionController.awaitResult()
		if (result.values.all { it == PermissionState.PermanentlyDenied }) {
			_permissionDialog.value = HomeState.PermissionDialogState(
				title = "Permission Denied",
				message = "Please, enable Bluetooth permissions in settings.",
				actionName = "Settings",
				onAction = {
					androidHelper.openAppSettings()
					_permissionDialog.value = null
				},
				onDismissRequest = {
					_permissionDialog.value = null
				},
			)
			return
		}

		val shouldShowEnableBluetoothDialog = bluetoothController.canEnableBluetooth
			&& !bluetoothController.state.value.isOn
			&& result.values.all { it == PermissionState.Granted }
		if (shouldShowEnableBluetoothDialog) {
			if (androidHelper.showEnableBluetoothDialog()) {
				action()
			}
			else {
				androidHelper.showToast("Please, enable Bluetooth to listen for bluetooth connections.")
			}
		}
		else {
			action()
		}
	}


	override fun onServiceRegistered() {
		// In some devices (At least on Realme 6 API level 30), when you close and open the app
		// again, if you were scanning it will continue scanning, which it's kind of a weird behavior,
		// so we just manually stop it ourselves.
		bluetoothController.stopScan()
		registeredScope.launch {
			bluetoothController.devices.collect { devices ->
				devices.forEach { device ->
					if (device.connectionState == BluetoothDevice.ConnectionState.Disconnected && device.address == _targetDeviceAddress.value) {
						_targetDeviceAddress.value = null
					}
				}
			}
		}

		registeredScope.launch {
			val previousStates = mutableMapOf<String, BluetoothDevice.ConnectionState>()

			bluetoothController.devices.collect { devices ->
				devices.forEach { device ->
					val previousState = previousStates[device.address] // Usa una ID única del dispositivo
					val currentState = device.connectionState

					if ((previousState == BluetoothDevice.ConnectionState.Connected || previousState == BluetoothDevice.ConnectionState.Disconnecting) &&
						currentState == BluetoothDevice.ConnectionState.Disconnected
					) {
						androidHelper.showToast("Device '${device.name}' disconnected")
					}

					previousStates[device.address] = currentState
				}
			}
		}
	}

	override fun onServiceUnregistered() {

	}
}
