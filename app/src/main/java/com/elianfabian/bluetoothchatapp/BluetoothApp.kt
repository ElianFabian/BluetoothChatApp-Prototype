package com.elianfabian.bluetoothchatapp

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.elianfabian.bluetoothchatapp.common.util.simplestack.compose.BasePreview

@Composable
fun BluetoothApp(
	content: @Composable (PaddingValues) -> Unit = {},
) {
	Scaffold(
		modifier = Modifier
			.fillMaxSize()
	) { innerPadding ->
		content(innerPadding)
	}
}


@Preview(showBackground = true)
@Composable
private fun Preview() = BasePreview {
	BluetoothApp()
}
