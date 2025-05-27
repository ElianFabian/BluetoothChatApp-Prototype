package com.elianfabian.bluetoothchatapp.home.domain

data class BluetoothDevice(
	val name: String?,
	val address: String,
	val pairingState: PairingState,
	val isConnected: Boolean,
) {
	enum class PairingState {
		None,
		Pairing,
		Paired;

		val isPaired: Boolean get() = this == Paired
	}
}
