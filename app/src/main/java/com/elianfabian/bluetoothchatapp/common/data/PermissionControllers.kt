package com.elianfabian.bluetoothchatapp.common.data

import android.Manifest
import android.os.Build
import com.elianfabian.bluetoothchatapp.common.domain.MultiplePermissionController
import com.elianfabian.bluetoothchatapp.common.domain.PermissionController
import com.elianfabian.bluetoothchatapp.common.domain.PermissionState

class ReadContactsPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BasePermissionControllerImpl(mainActivityHolder) {
	override val permissionName: String
		get() = android.Manifest.permission.READ_CONTACTS
}

class PostNotificationsPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BasePermissionControllerImpl(mainActivityHolder) {
	override val permissionName: String
		get() = android.Manifest.permission.POST_NOTIFICATIONS

	override fun getCurrentState(): PermissionState {
		if (Build.VERSION.SDK_INT >= 33) {
			return super.getCurrentState()
		}
		return PermissionState.Granted
	}

	override suspend fun awaitResult(): PermissionState {
		if (Build.VERSION.SDK_INT >= 33) {
			return super.awaitResult()
		}
		return PermissionState.Granted
	}
}


class BluetoothPermissionController(
	mainActivityHolder: MainActivityHolder,
) : BaseMultiplePermissionControllerImpl(mainActivityHolder) {
	override val permissionNames: List<String>
		get() = buildList {
			if (Build.VERSION.SDK_INT >= 31) {
				add(Manifest.permission.BLUETOOTH_SCAN)
				add(Manifest.permission.BLUETOOTH_CONNECT)
			}
			else if (Build.VERSION.SDK_INT >= 23) {
				add(Manifest.permission.ACCESS_FINE_LOCATION)
			}
		}
}
