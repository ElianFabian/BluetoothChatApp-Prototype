package com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.callbacks

interface ApplicationBackgroundStateChangeCallback {

	fun onAppEnteredForeground()
	fun onAppEnteredBackground()
}
