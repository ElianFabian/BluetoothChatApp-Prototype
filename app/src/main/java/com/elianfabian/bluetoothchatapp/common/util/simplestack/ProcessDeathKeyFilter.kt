package com.elianfabian.bluetoothchatapp.common.util.simplestack

import com.zhuinden.simplestack.KeyFilter

class ProcessDeathKeyFilter : KeyFilter {

	override fun filterHistory(restoredKeys: List<Any>): List<Any> {
		return restoredKeys
	}
}
