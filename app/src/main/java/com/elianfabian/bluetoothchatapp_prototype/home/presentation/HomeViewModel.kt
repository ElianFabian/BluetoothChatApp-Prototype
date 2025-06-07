package com.elianfabian.bluetoothchatapp_prototype.home.presentation

import android.os.Build
import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp_prototype.common.domain.MultiplePermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.PermissionController
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
	private val accessFineLocationPermissionController: PermissionController,
	private val androidHelper: AndroidHelper,
	private val registeredScope: CoroutineScope,
) : ScopedServices.Registered {

	private val _permissionDialog = MutableStateFlow<HomeState.PermissionDialogState?>(null)
	private val _messages = MutableStateFlow<List<BluetoothMessage>>(emptyList())
	private val _enteredMessage = MutableStateFlow("")
	private val _enteredBluetoothDeviceName = MutableStateFlow<String?>(null)
	private val _useSecureConnection = MutableStateFlow(true)
	private val _selectedDevice = MutableStateFlow<HomeState.SelectedDevice>(HomeState.SelectedDevice.None)

	val state = combineTuple(
		bluetoothController.devices,
		bluetoothController.isScanning,
		bluetoothController.state,
		_permissionDialog,
		bluetoothController.bluetoothDeviceName,
		_messages,
		_enteredMessage,
		bluetoothController.isWaitingForConnection,
		_enteredBluetoothDeviceName,
		_useSecureConnection,
		_selectedDevice,
	).map {
			(
				devices, isScanning, bluetoothState, permissionDialog, bluetoothName,
				messages, enteredMessage, isWaitingForConnection, enteredBluetoothDeviceName,
				useSecureConnection, selectedDevice,
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
			selectedDevice = selectedDevice,
			connectedDevices = devices.filter {
				it.connectionState == BluetoothDevice.ConnectionState.Connected
			},
			isScanning = isScanning,
			isBluetoothSupported = bluetoothController.isBluetoothSupported,
			isBluetoothOn = bluetoothState.isOn,
			permissionDialog = permissionDialog,
			bluetoothDeviceName = bluetoothName,
			messages = messages,
			enteredMessage = enteredMessage,
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
		),
	)

	fun sendAction(action: HomeAction) {
		when (action) {
			is HomeAction.StartScan -> {
				registeredScope.launch {
					executeIfBluetoothRequirementsAreSatisfied {
						if (Build.VERSION.SDK_INT < 31) {
							val result = accessFineLocationPermissionController.request()
							if (result == PermissionState.PermanentlyDenied) {
								_permissionDialog.value = HomeState.PermissionDialogState(
									title = "Permission Denied",
									message = "Please, enable location permissions in settings to allow scanning.",
									actionName = "Settings",
									onAction = {
										androidHelper.openAppSettings()
										_permissionDialog.value = null
									},
									onDismissRequest = {
										_permissionDialog.value = null
									},
								)
								return@executeIfBluetoothRequirementsAreSatisfied
							}
							if (!result.isGranted) {
								androidHelper.showToast("Location permission is needed to scan")
								return@executeIfBluetoothRequirementsAreSatisfied
							}
						}
						if (!bluetoothController.startScan()) {
							// In some devices, at least for API level 29, if this returns false we likely
							// need to turn on location
							// Maybe in some cases it is not the case, we'll have to see
							// The ideal solution would be to know in which concrete cases this is needed
							// I think it is a combination of manufacturer and API level
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
				val selectedDevice = _selectedDevice.value
				if (selectedDevice == HomeState.SelectedDevice.None) {
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
				if (selectedDevice is HomeState.SelectedDevice.Device) {
					if (connectedDevices.none { it.address == selectedDevice.device.address }) {
						androidHelper.showToast("Please, connect to the device with address: ${selectedDevice.device.address} before sending a message.")
						return
					}
				}
				registeredScope.launch {
					when (selectedDevice) {
						is HomeState.SelectedDevice.Device -> {
							val message = bluetoothController.trySendMessage(
								address = selectedDevice.device.address,
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
						is HomeState.SelectedDevice.AllDevices -> {
							bluetoothController.devices.value.filter {
								it.connectionState == BluetoothDevice.ConnectionState.Connected
							}.forEach { connectedDevice ->
								val message = bluetoothController.trySendMessage(
									address = connectedDevice.address,
									message = _enteredMessage.value,
								)
								if (message != null) {
									_messages.update {
										it + message
									}
								}
							}
							_enteredMessage.value = ""
							androidHelper.closeKeyboard()
						}
						is HomeState.SelectedDevice.None -> Unit
					}

				}
			}
			is HomeAction.EnterMessage -> {
				_enteredMessage.value = action.message
			}
			is HomeAction.ClickPairedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = HomeState.SelectedDevice.Device(action.device)
					return
				}
				registeredScope.launch {
					if (action.device.connectionState == BluetoothDevice.ConnectionState.Connecting) {
						bluetoothController.cancelConnectionAttempt(action.device.address).also {
							println("$$$ cancelConnectionAttempt result1 = $it")
						}
						return@launch
					}
					val result = if (_useSecureConnection.value) {
						bluetoothController.connectToDevice(action.device.address)
					}
					else bluetoothController.connectToDeviceInsecurely(action.device.address)
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
			is HomeAction.ClickScannedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					_selectedDevice.value = HomeState.SelectedDevice.Device(action.device)
					return
				}
				registeredScope.launch {
					if (action.device.connectionState == BluetoothDevice.ConnectionState.Connecting) {
						bluetoothController.cancelConnectionAttempt(action.device.address).also {
							println("$$$ cancelConnectionAttempt result2 = $it")
						}
						return@launch
					}
					val result = if (_useSecureConnection.value) {
						bluetoothController.connectToDevice(action.device.address)
					}
					else bluetoothController.connectToDeviceInsecurely(action.device.address)

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
						if (!bluetoothController.disconnectFromDevice(action.device.address)) {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is HomeAction.LongClickScannedDevice -> {
				if (action.device.connectionState == BluetoothDevice.ConnectionState.Connected) {
					registeredScope.launch {
						if (!bluetoothController.disconnectFromDevice(action.device.address)) {
							androidHelper.showToast("Could not disconnect from: ${action.device.name}")
						}
					}
				}
			}
			is HomeAction.ClickMessage -> {
				if (!action.message.isFromLocalUser) {
					val targetDevice = bluetoothController.devices.value.find { device ->
						device.address == action.message.senderAddress
					} ?: return

					_selectedDevice.value = HomeState.SelectedDevice.Device(targetDevice)
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
			is HomeAction.SelectTargetDeviceToMessage -> {
				_selectedDevice.value = HomeState.SelectedDevice.Device(action.connectedDevice)
			}
			HomeAction.SelectAllDevicesToMessage -> {
				_selectedDevice.value = HomeState.SelectedDevice.AllDevices
			}
		}
	}

	private suspend fun executeIfBluetoothRequirementsAreSatisfied(action: suspend () -> Unit) {
		val result = bluetoothPermissionController.request()
		println("$$$$ bluetooth permission result = $result")
		if (result.isNotEmpty() && result.values.all { it == PermissionState.PermanentlyDenied }) {
			_permissionDialog.value = HomeState.PermissionDialogState(
				title = "Permission Denied",
				message = "Please, enable bluetooth permissions in settings.",
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
		if (result.values.any { !it.isGranted }) {
			androidHelper.showToast("You have to grant the permissions to perform the operation")
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
			return
		}

		action()
	}


	override fun onServiceRegistered() {
		// In some devices (At least on Realme 6 API level 30), when you close and open the app
		// again, if you were scanning it will continue scanning, which it's kind of a weird behavior,
		// so we just manually stop it ourselves.
		bluetoothController.stopScan()
		registeredScope.launch {
			bluetoothController.state.collect { state ->
				if (state == BluetoothController.BluetoothState.Off) {
					_enteredBluetoothDeviceName.value = null
				}
			}
		}
		registeredScope.launch {
			bluetoothController.events.collect { event ->
				when (event) {
					is BluetoothController.Event.OnDeviceConnected -> {
						if (_selectedDevice.value == HomeState.SelectedDevice.None) {
							_selectedDevice.value = HomeState.SelectedDevice.Device(event.connectedDevice)
						}
						androidHelper.showToast("Device connected: '${event.connectedDevice.name}'")
					}
					is BluetoothController.Event.OnDeviceDisconnected -> {
						androidHelper.showToast("Device disconnected: '${event.disconnectedDevice.name}'")

						_selectedDevice.update { selection ->
							when (selection) {
								is HomeState.SelectedDevice.AllDevices -> {
									val connectedDevices = bluetoothController.devices.value.filter {
										it.connectionState == BluetoothDevice.ConnectionState.Connected
									}
									if (connectedDevices.isNotEmpty()) {
										HomeState.SelectedDevice.AllDevices
									}
									else HomeState.SelectedDevice.None
								}
								is HomeState.SelectedDevice.Device -> {
									HomeState.SelectedDevice.None
								}
								is HomeState.SelectedDevice.None -> {
									HomeState.SelectedDevice.None
								}
							}
						}
					}
				}
			}
		}
	}

	override fun onServiceUnregistered() {

	}
}
