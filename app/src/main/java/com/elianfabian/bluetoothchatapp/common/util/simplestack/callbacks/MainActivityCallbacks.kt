package com.elianfabian.bluetoothchatapp.common.util.simplestack.callbacks

import androidx.fragment.app.FragmentActivity

interface MainActivityCallbacks {
	fun onCreateMainActivity(activity: FragmentActivity)
	fun onDestroyMainActivity(activity: FragmentActivity)
}
