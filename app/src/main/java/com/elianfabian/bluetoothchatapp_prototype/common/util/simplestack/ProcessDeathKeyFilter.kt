package com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack

import com.zhuinden.simplestack.KeyFilter

class ProcessDeathKeyFilter : KeyFilter {

	override fun filterHistory(restoredKeys: List<Any>): List<Any> {
		return restoredKeys
	}
}
