package com.elianfabian.bluetoothchatapp_prototype.home.di

import com.elianfabian.bluetoothchatapp_prototype.common.data.BluetoothPermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.CoroutineScopedServiceModule
import com.elianfabian.bluetoothchatapp_prototype.home.data.BluetoothControllerImpl
import com.elianfabian.bluetoothchatapp_prototype.home.domain.BluetoothController
import com.elianfabian.bluetoothchatapp_prototype.home.presentation.HomeViewModel
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup

data object HomeModule : CoroutineScopedServiceModule() {

	override fun bindModuleServices(serviceBinder: ServiceBinder) {
		val bluetoothController: BluetoothController = BluetoothControllerImpl(
			mainActivityHolder = serviceBinder.lookup(),
			registeredScope = registeredScope,
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
