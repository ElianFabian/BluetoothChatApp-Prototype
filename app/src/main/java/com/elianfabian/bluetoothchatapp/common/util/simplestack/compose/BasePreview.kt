package com.elianfabian.bluetoothchatapp.common.util.simplestack.compose

import androidx.compose.runtime.Composable
import com.elianfabian.bluetoothchatapp.ui.theme.BluetoothChatAppTheme
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestackcomposeintegration.core.BackstackProvider

@Composable
fun BasePreview(
	content: @Composable () -> Unit,
) {
	BackstackProvider(backstack = Backstack()) {
		BluetoothChatAppTheme {
			content()
		}
	}
}
