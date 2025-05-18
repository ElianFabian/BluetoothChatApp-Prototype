package com.elianfabian.bluetoothchatapp.common.util.simplestack

import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.ServiceSearchMode

inline fun <reified T> Backstack.forEachServiceOfType(
	searchMode: ServiceSearchMode = ServiceSearchMode.INCLUDE_PARENT_SERVICE,
	block: (service: T) -> Unit,
) {
	findServices(searchMode).forEach { result ->
		val service = result.service
		if (service is T) {
			block(service)
		}
	}
}
