package com.elianfabian.bluetoothchatapp.common.util.simplestack.callbacks

interface ApplicationBackgroundStateChangeCallback {

	fun onAppEnteredForeground()
	fun onAppEnteredBackground()
}
