package com.elianfabian.bluetoothchatapp_prototype.chat.data

import com.elianfabian.bluetoothchatapp_prototype.chat.domain.BluetoothMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

fun BluetoothMessage.toByteArray(): ByteArray {
	val byteStream = ByteArrayOutputStream()
	DataOutputStream(byteStream).use { dataStream ->
		dataStream.writeUTF(senderName)
		dataStream.writeUTF(content)
	}
	return byteStream.toByteArray()
}

fun ByteArray.toBluetoothMessage(
	isFromLocalUser: Boolean,
): BluetoothMessage? {
	try {
		val input = DataInputStream(ByteArrayInputStream(this))
		val message = input.readBytes().contentToString()
		return BluetoothMessage(
			senderName = null,
			senderAddress = "",
			content = message,
			isFromLocalUser = isFromLocalUser,
		)
	} catch (e: Exception) {
		return null
	}
}
