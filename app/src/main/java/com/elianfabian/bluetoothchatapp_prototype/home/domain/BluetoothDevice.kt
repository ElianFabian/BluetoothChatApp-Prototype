package com.elianfabian.bluetoothchatapp_prototype.home.domain

data class BluetoothDevice(
	val name: String?,
	val address: String,
	val pairingState: PairingState,
	val connectionState: ConnectionState,
) {
	enum class PairingState {
		None,
		Pairing,
		Paired;

		val isPaired: Boolean get() = this == Paired
	}

	enum class ConnectionState {
		Connected,
		Connecting,
		Disconnected,
		Disconnecting,
	}
}
