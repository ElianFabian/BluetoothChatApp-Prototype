package com.elianfabian.bluetoothchatapp.chat.data

import com.elianfabian.bluetoothchatapp.chat.domain.BluetoothMessage
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
): BluetoothMessage {
	val input = DataInputStream(ByteArrayInputStream(this))
	val senderName = input.readUTF()
	val message = input.readUTF()
	return BluetoothMessage(senderName, message, isFromLocalUser)
}
