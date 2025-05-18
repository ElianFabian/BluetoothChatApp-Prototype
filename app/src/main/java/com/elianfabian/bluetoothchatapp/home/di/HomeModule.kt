package com.elianfabian.bluetoothchatapp.home.di

import com.elianfabian.bluetoothchatapp.common.data.BluetoothPermissionController
import com.elianfabian.bluetoothchatapp.common.util.simplestack.CoroutineScopedServiceModule
import com.elianfabian.bluetoothchatapp.home.data.BluetoothControllerImpl
import com.elianfabian.bluetoothchatapp.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp.home.presentation.HomeViewModel
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup

data object HomeModule : CoroutineScopedServiceModule() {

	override fun bindModuleServices(serviceBinder: ServiceBinder) {
		val bluetoothController: BluetoothController = BluetoothControllerImpl(
			mainActivityHolder = serviceBinder.lookup(),
		)
		val bluetoothPermissionController = BluetoothPermissionController(
			mainActivityHolder = serviceBinder.lookup(),
		)
		val viewModel = HomeViewModel(
			bluetoothController = bluetoothController,
			bluetoothPermissionController = bluetoothPermissionController,
			androidHelper = serviceBinder.lookup(),
			registeredScope = registeredScope,
		)

		with(serviceBinder) {
			add(bluetoothPermissionController)
			add(bluetoothController)
			add(viewModel)
		}
	}
}
