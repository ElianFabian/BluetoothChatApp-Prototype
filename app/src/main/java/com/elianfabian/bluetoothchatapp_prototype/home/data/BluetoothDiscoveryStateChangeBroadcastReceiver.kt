package com.elianfabian.bluetoothchatapp_prototype.home.data

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BluetoothDiscoveryStateChangeBroadcastReceiver(
	private val onDiscoveryStateChange: (isDiscovering: Boolean) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
				onDiscoveryStateChange(true)
			}
			BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
				onDiscoveryStateChange(false)
			}
		}
	}
}
