package com.elianfabian.bluetoothchatapp_prototype.home.presentation

import android.os.Build
import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp_prototype.common.domain.MultiplePermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.PermissionState
import com.elianfabian.bluetoothchatapp_prototype.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp_prototype.home.domain.BluetoothDevice
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
	private val bluetoothController: BluetoothController,
	private val bluetoothPermissionController: MultiplePermissionController,
	private val androidHelper: AndroidHelper,
	private val registeredScope: CoroutineScope,
) : ScopedServices.Registered {

	private val _permissionDialog = MutableStateFlow<HomeState.PermissionDialogState?>(null)
	private val _messages = MutableStateFlow<List<BluetoothMessage>>(emptyList())
	private val _enteredMessage = MutableStateFlow("")
	private val _targetDeviceAddress = MutableStateFlow<String?>(null)
	private val _enteredBluetoothDeviceName = MutableStateFlow<String?>(null)
	private val _useSecureConnection = MutableStateFlow(true)

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
		_enteredBluetoothDeviceName,
		_useSecureConnection,
	).map {
			(
				devices, isScanning, bluetoothState, state, permissionDialog, bluetoothName,
				messages, enteredMessage, targetDeviceAddress, isWaitingForConnection, enteredBluetoothDeviceName,
				useSecureConnection,
			),
		->
		HomeState(
			pairedDevices = devices.filter {
				it.pairingState == BluetoothDevice.PairingState.Paired
			}.sortedByDescending {
				it.connectionState == BluetoothDevice.ConnectionState.Connected
			},
			scannedDevices = devices.filter {
				it.pairingState != BluetoothDevice.PairingState.Paired
			}.sortedByDescending {
				it.connectionState == BluetoothDevice.ConnectionState.Connected
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
			enteredBluetoothDeviceName = enteredBluetoothDeviceName,
			useSecureConnection = useSecureConnection,
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
						val result = if (_useSecureConnection.value) {
							bluetoothController.startBluetoothServer()
						}
						else bluetoothController.startInsecureBluetoothServer()
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
					val result = if (_useSecureConnection.value) {
						bluetoothController.connectToDevice(action.device.address)
					}
					else bluetoothController.connectToDeviceInsecure(action.device.address)
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
					val result = if (_useSecureConnection.value) {
						bluetoothController.connectToDevice(action.device.address)
					}
					else bluetoothController.connectToDeviceInsecure(action.device.address)

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
			is HomeAction.EditBluetoothDeviceName -> {
				_enteredBluetoothDeviceName.value = bluetoothController.bluetoothDeviceName.value
			}
			is HomeAction.EnterBluetoothDeviceName -> {
				_enteredBluetoothDeviceName.value = action.bluetoothDeviceName
			}
			is HomeAction.SaveBluetoothDeviceName -> {
				val newBluetoothDeviceName = _enteredBluetoothDeviceName.value ?: return

				if (bluetoothController.setBluetoothDeviceName(newBluetoothDeviceName)) {
					_enteredBluetoothDeviceName.value = null
				}
				else {
					androidHelper.showToast("Couldn't change the bluetooth name")
				}
			}
			is HomeAction.CheckUseSecureConnection -> {
				_useSecureConnection.value = action.enabled
			}
		}
	}

	private suspend fun executeIfBluetoothRequirementsAreSatisfied(action: suspend () -> Unit) {
		val result = bluetoothPermissionController.request()
		if (result.values.all { it == PermissionState.PermanentlyDenied }) {
			val feature = if (Build.VERSION.SDK_INT >= 31) {
				"bluetooth"
			}
			else "location"

			_permissionDialog.value = HomeState.PermissionDialogState(
				title = "Permission Denied",
				message = "Please, enable $feature permissions in settings.",
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
				androidHelper.showToast("Please, enable Bluetooth to perform the operation.")
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
					val previousState = previousStates[device.address]
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
		registeredScope.launch {
			bluetoothController.state.collect { state ->
				if (state == BluetoothController.BluetoothState.Off) {
					_enteredBluetoothDeviceName.value = null
				}
			}
		}
	}

	override fun onServiceUnregistered() {

	}
}
