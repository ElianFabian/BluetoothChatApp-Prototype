package com.elianfabian.bluetoothchatapp.home.data

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BluetoothStateChangeBroadcastReceiver(
	private val onStateChange: (state: Int) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
			return
		}
		val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
		when (state) {
			BluetoothAdapter.STATE_OFF,
			BluetoothAdapter.STATE_TURNING_OFF,
			BluetoothAdapter.STATE_ON,
			BluetoothAdapter.STATE_TURNING_ON,
				-> {
				onStateChange(state)
			}
		}
	}
}
