package com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BluetoothDeviceNameChangeBroadcastReceiver(
	private val onNameChange: (newName: String) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED) {
			return
		}

		val newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME) ?: ""
		onNameChange(newName)
	}
}
