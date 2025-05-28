package com.elianfabian.bluetoothchatapp.chat.domain

data class BluetoothMessage(
	val senderName: String?,
	val senderAddress: String,
	val content: String,
	val isFromLocalUser: Boolean,
)
