package com.elianfabian.bluetoothchatapp.home.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
import com.elianfabian.bluetoothchatapp.common.util.simplestack.compose.BasePreview
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothDevice
import com.elianfabian.bluetoothchatapp.home.presentation.HomeAction
import com.elianfabian.bluetoothchatapp.home.presentation.HomeState
import kotlin.random.Random

@Composable
fun DeviceScreen(
	state: HomeState,
	onAction: (action: HomeAction) -> Unit,
) {
	if (state.permissionDialog != null) {
		val dialogState = state.permissionDialog
		Dialog(
			properties = DialogProperties(
				dismissOnBackPress = true,
				usePlatformDefaultWidth = true,
			),
			onDismissRequest = {
				dialogState.onDismissRequest()
			}
		) {
			Card {
				Column(
					modifier = Modifier.padding(16.dp)
				) {
					Text(
						text = dialogState.message,
					)
					Spacer(modifier = Modifier.height(16.dp))
					Button(
						onClick = {
							dialogState.onAction()
						},
						modifier = Modifier
							.fillMaxWidth()
					) {
						Text(dialogState.actionName)
					}
				}
			}
		}
	}

	if (!state.isBluetoothSupported) {
		Column(
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier
				.fillMaxSize()
				.padding(all = 16.dp)
		) {
			Text(
				text = "Your device does not support Bluetooth.",
				fontSize = 18.sp,
				fontWeight = FontWeight.Bold,
			)
		}
	}
	else {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(
					WindowInsets.safeDrawing
						.asPaddingValues()
				)
				.padding(all = 16.dp)
		) {
			if (!state.isBluetoothOn) {
				Text(
					text = "Bluetooth is off.",
					fontWeight = FontWeight.Bold,
					fontSize = 24.sp,
					color = Color.Red,
				)
			}
			Column(
				modifier = Modifier
					.fillMaxWidth()
			) {
				Button(
					onClick = {
						onAction(HomeAction.SendMessage)
					},
				) {
					Text("Send")
				}
				TextField(
					value = state.enteredTargetDeviceAddress,
					onValueChange = { value ->
						onAction(HomeAction.EnterMessage(value))
					},
					placeholder = {
						Text("Target device address")
					},
				)
				TextField(
					value = state.enteredMessage,
					onValueChange = { value ->
						onAction(HomeAction.EnterMessage(value))
					},
					placeholder = {
						Text("Message to send")
					},
				)
			}
			Text("Device name: ${state.bluetoothDeviceName}")
			Text("Connection state: ${state.connectionState}")
			Row {
				Button(
					onClick = {
						onAction(HomeAction.MakeDeviceDiscoverable)
					}
				) {
					Text("Make discoverable")
				}
				Spacer(modifier = Modifier.width(6.dp))
				Button(
					onClick = {
						onAction(HomeAction.OpenBluetoothSettings)
					}
				) {
					Text("Bluetooth settings")
				}
			}
			Button(
				onClick = {
					onAction(HomeAction.OpenDeviceInfoSettings)
				}
			) {
				Text("Device info settings")
			}
			Spacer(modifier = Modifier.height(16.dp))
			BluetoothDeviceList(
				state = state,
				onConnectedDeviceClick = { device ->
					onAction(HomeAction.ClickConnectedDevice(device))
				},
				onPairedDeviceClick = { device ->
					onAction(HomeAction.ClickPairedDevice(device))
				},
				onScannedDeviceClick = { device ->
					onAction(HomeAction.ClickScannedDevice(device))
				},
				modifier = Modifier
					.fillMaxWidth()
					.weight(1F)
			)
			Row(
				horizontalArrangement = Arrangement.SpaceAround,
				modifier = Modifier
					.fillMaxWidth()
			) {
				Button(
					onClick = {
						onAction(HomeAction.StartScan)
					}
				) {
					Text(text = "Scan")
				}
				Button(
					onClick = {
						onAction(HomeAction.StopScan)
					}
				) {
					Text(text = "Stop scan")
				}
				Button(
					onClick = {
						onAction(HomeAction.StartServer)
					}
				) {
					Text(text = "Start server")
				}
			}
		}
	}
}

@Composable
private fun BluetoothDeviceList(
	modifier: Modifier = Modifier,
	state: HomeState,
	onConnectedDeviceClick: (device: BluetoothDevice) -> Unit,
	onPairedDeviceClick: (device: BluetoothDevice) -> Unit,
	onScannedDeviceClick: (device: BluetoothDevice) -> Unit,
) {
	val lazyListState = rememberLazyListState()
	LaunchedEffect(state.messages) {
		if (lazyListState.layoutInfo.totalItemsCount == 0) {
			return@LaunchedEffect
		}
		lazyListState.scrollToItem(lazyListState.layoutInfo.totalItemsCount)
	}
	LazyColumn(
		state = lazyListState,
		verticalArrangement = Arrangement.spacedBy(3.dp),
		modifier = modifier
	) {
		item {
			Text(
				text = "Connected devices",
				fontWeight = FontWeight.Bold,
				fontSize = 24.sp,
			)
		}
		items(state.connectedDevices) { device ->
			BluetoothDeviceItem(
				name = device.name,
				address = device.address,
				isConnected = device.isConnected,
				pairingState = device.pairingState,
				onClick = {
					onConnectedDeviceClick(device)
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 6.dp)
			)
		}
		item {
			Text(
				text = "Paired devices",
				fontWeight = FontWeight.Bold,
				fontSize = 24.sp,
			)
		}
		items(state.pairedDevices) { device ->
			BluetoothDeviceItem(
				name = device.name,
				address = device.address,
				isConnected = device.isConnected,
				pairingState = device.pairingState,
				onClick = {
					onPairedDeviceClick(device)
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 6.dp)
			)
		}
		item {
			Spacer(Modifier.height(16.dp))
		}
		item {
			Row(
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = "Scanned devices",
					fontWeight = FontWeight.Bold,
					fontSize = 24.sp,
				)
				if (state.isScanning) {
					Spacer(Modifier.width(6.dp))
					CircularProgressIndicator(
						strokeWidth = 3.dp,
						modifier = Modifier
							.size(20.dp)
					)
				}
			}
		}
		items(state.scannedDevices) { device ->
			BluetoothDeviceItem(
				name = device.name,
				address = device.address,
				isConnected = device.isConnected,
				pairingState = device.pairingState,
				onClick = {
					onScannedDeviceClick(device)
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 6.dp)
			)
		}
		item {
			Spacer(Modifier.height(16.dp))
			Text(
				text = "Messages",
				fontWeight = FontWeight.Bold,
				fontSize = 24.sp,
			)
		}
		items(state.messages) { message ->
			Text(
				text = message.content,
				color = if (message.isFromLocalUser) Color.Blue else Color.Green,
				modifier = Modifier
					.padding(vertical = 6.dp)
			)
		}
	}
}

@Composable
private fun BluetoothDeviceItem(
	name: String?,
	address: String,
	isConnected: Boolean,
	pairingState: BluetoothDevice.PairingState,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant)
			.padding(12.dp)
			.clickable {
				onClick()
			}
	) {
		if (pairingState == BluetoothDevice.PairingState.Pairing) {
			CircularProgressIndicator(
				strokeWidth = 3.dp,
				modifier = Modifier
					.size(20.dp)
			)
		}
		Column {
			if (name != null) {
				Text(
					text = name,
					fontSize = 18.sp,
					lineHeight = 30.sp,
				)
				Spacer(modifier = Modifier.height(4.dp))
			}
			Text(
				text = address,
				fontSize = 18.sp,
				lineHeight = 30.sp,
				color = if (isConnected) Color.Green else MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun Preview() = BasePreview {

	val deviceNames = listOf(
		"Device 1",
		"Device 2",
		"Device 3",
	)

	val devices = deviceNames.map { name ->
		BluetoothDevice(
			name = name,
			address = "123:45:67:89:AB:$name",
			isConnected = false,
			pairingState = when (Random.nextInt(0, 3)) {
				0 -> BluetoothDevice.PairingState.Paired
				1 -> BluetoothDevice.PairingState.Pairing
				else -> BluetoothDevice.PairingState.None
			},
		)
	}

	DeviceScreen(
		state = HomeState(
			bluetoothDeviceName = "Bluetooth Device",
			pairedDevices = devices.filter { it.pairingState.isPaired },
			scannedDevices = devices.filter { !it.pairingState.isPaired },
			isBluetoothSupported = true,
			isScanning = true,
			isBluetoothOn = true,
//			permissionDialog = HomeState.PermissionDialogState(
//				title = "Permission Denied",
//				message = "Please, enable Bluetooth permissions in settings.",
//				actionName = "Settings",
//				onAction = {},
//				onDismissRequest = {},
//			),
			messages = listOf(
				"Hey",
				"Hello",
				"How are you?",
				"Good",
				"How about you?",
				"Fine, thanks",
				"See you later",
				"Bye",
				"Take care",
//				"See you soon",
//				"Have a nice day",
//				"Goodbye",
//				"See you next time",
			).mapIndexed { index, s ->
				BluetoothMessage(
					content = s,
					isFromLocalUser = index % 2 == 0,
					senderName = null,
				)
			}
		),
		onAction = {},
	)
}
