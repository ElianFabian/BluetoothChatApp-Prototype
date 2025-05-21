package com.elianfabian.bluetoothchatapp.common.util

import java.nio.ByteBuffer

fun ByteBuffer.putString(str: String) {
    val bytes = str.toByteArray(Charsets.UTF_8)
    putInt(bytes.size)
    put(bytes)
}

fun ByteBuffer.getString(): String {
    val length = getInt()
    println("$$ getString.length = $length")
    val bytes = ByteArray(length)
    get(bytes)
    return String(bytes, Charsets.UTF_8)
}
