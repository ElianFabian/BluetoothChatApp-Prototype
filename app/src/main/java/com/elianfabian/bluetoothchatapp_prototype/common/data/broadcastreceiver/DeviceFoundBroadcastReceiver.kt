package com.elianfabian.bluetoothchatapp_prototype.common.data.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.bluetoothchatapp_prototype.home.data.AndroidBluetoothDevice

class DeviceFoundBroadcastReceiver(
	private val onDeviceFound: (device: AndroidBluetoothDevice) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == AndroidBluetoothDevice.ACTION_FOUND) {
			val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE)
			if (device != null) {
				onDeviceFound(device)
			}
		}
	}
}
