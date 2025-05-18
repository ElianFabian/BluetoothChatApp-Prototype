package com.elianfabian.bluetoothchatapp.home.presentation

import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp.common.data.BluetoothPermissionController
import com.elianfabian.bluetoothchatapp.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp.common.domain.PermissionState
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothController
import com.zhuinden.flowcombinetuplekt.combineTuple
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
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
	private val _enteredMessage = MutableStateFlow<String>("")

	val state = combineTuple(
		bluetoothController.pairedDevices,
		bluetoothController.scannedDevices,
		bluetoothController.isScanning,
		bluetoothController.state,
		bluetoothPermissionController.state,
		_permissionDialog,
		androidHelper.bluetoothName,
		bluetoothController.connectionState,
		_messages,
		_enteredMessage,
	).map { (pairedDevices, scannedDevices, isScanning, bluetoothState, state, permissionDialog, bluetoothName, connectionState, messages, enteredMessage) ->
		HomeState(
			pairedDevices = pairedDevices,
			scannedDevices = scannedDevices,
			isScanning = isScanning,
			isBluetoothSupported = bluetoothController.isBluetoothSupported,
			isBluetoothOn = bluetoothState.isOn,
			permissionState = state.values.toList(),
			permissionDialog = permissionDialog,
			bluetoothDeviceName = bluetoothName,
			connectionState = connectionState,
			messages = messages,
			enteredMessage = enteredMessage,
		)
	}.stateIn(
		scope = registeredScope,
		started = SharingStarted.WhileSubscribed(5000),
		initialValue = HomeState(
			bluetoothDeviceName = androidHelper.bluetoothName.value,
			isBluetoothSupported = bluetoothController.isBluetoothSupported,
			isBluetoothOn = bluetoothController.state.value.isOn,
			permissionState = bluetoothPermissionController.state.value.values.toList(),
		),
	)

	fun sendAction(action: HomeAction) {
		when (action) {
			is HomeAction.StartScan -> {
				registeredScope.launch {
					executeIfSatisfyBluetoothRequirements {
						bluetoothController.startScan()
					}
				}
			}
			is HomeAction.StopScan -> {
				bluetoothController.stopScan()
			}
			is HomeAction.StartServer -> {
				registeredScope.launch {
					executeIfSatisfyBluetoothRequirements {
						bluetoothController.startBluetoothServer().collect { result ->
							if (result is BluetoothController.ConnectionResult.Message) {
								_messages.update {
									it + result.message
								}
							}
						}
					}
				}
			}
			is HomeAction.OpenBluetoothSettings -> {
				androidHelper.openBluetoothSettings()
			}
			is HomeAction.OpenDeviceInfoSettings -> {
				androidHelper.openDeviceInfoSettings()
			}
			is HomeAction.MakeDeviceDiscoverable -> {
				androidHelper.makeDeviceDiscoverable {

				}
			}
			is HomeAction.SendMessage -> {
				registeredScope.launch {
					val message = bluetoothController.trySendMessage(_enteredMessage.value)
					if (message != null) {
						_messages.update {
							it + message
						}
						_enteredMessage.value = ""
					}
				}
			}
			is HomeAction.EnterMessage -> {
				_enteredMessage.value = action.message
			}
			is HomeAction.ClickPairedDevice -> {
				registeredScope.launch {
					bluetoothController.connectToDevice(action.device).collect { result ->
						if (result is BluetoothController.ConnectionResult.Message) {
							_messages.update {
								it + result.message
							}
						}
					}
				}
			}
			is HomeAction.ClickScannedDevice -> {
				registeredScope.launch {
					bluetoothController.connectToDevice(action.device).collect { result ->
						if (result is BluetoothController.ConnectionResult.Message) {
							_messages.update {
								it + result.message
							}
						}
					}
				}
			}
		}
	}

	private suspend fun executeIfSatisfyBluetoothRequirements(action: suspend () -> Unit) {
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
			androidHelper.showEnableBluetoothDialog { enabled ->
				if (enabled) {
					registeredScope.launch {
						action()
					}
				}
				else {
					androidHelper.showToast("Please, enable Bluetooth to listen for bluetooth connections.")
				}
			}
		}
		else {
			action()
		}
	}


	override fun onServiceRegistered() {

	}

	override fun onServiceUnregistered() {

	}
}
