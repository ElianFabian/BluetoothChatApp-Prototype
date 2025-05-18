package com.elianfabian.bluetoothchatapp.common.data

import androidx.fragment.app.FragmentActivity
import com.elianfabian.bluetoothchatapp.common.util.simplestack.callbacks.MainActivityCallbacks

class MainActivityHolder(
	activity: FragmentActivity,
) : MainActivityCallbacks {

	private var _mainActivity: FragmentActivity? = activity
	val mainActivity: FragmentActivity get() = _mainActivity ?: throw IllegalStateException("MainActivity has not been initialized.")


	override fun onCreateMainActivity(activity: FragmentActivity) {
		_mainActivity = activity
	}

	override fun onDestroyMainActivity(activity: FragmentActivity) {
		_mainActivity = null
	}
}
