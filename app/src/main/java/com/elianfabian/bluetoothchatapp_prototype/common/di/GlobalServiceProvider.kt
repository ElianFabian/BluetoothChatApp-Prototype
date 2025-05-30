package com.elianfabian.bluetoothchatapp_prototype.common.di

import android.app.Application
import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.elianfabian.bluetoothchatapp_prototype.common.data.AndroidHelperImpl
import com.elianfabian.bluetoothchatapp_prototype.common.data.MainActivityHolder
import com.elianfabian.bluetoothchatapp_prototype.common.data.ReadContactsPermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import com.zhuinden.simplestack.Backstack
import com.zhuinden.simplestack.GlobalServices
import com.zhuinden.simplestack.ServiceBinder
import com.zhuinden.simplestackextensions.servicesktx.add
import com.zhuinden.simplestackextensions.servicesktx.lookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GlobalServiceProvider(
	private val application: Application,
	mainActivity: FragmentActivity,
) : GlobalServices.Factory {

	private var _mainActivity: FragmentActivity? = mainActivity

	override fun create(backstack: Backstack): GlobalServices {

		val applicationContext: Context = application
		val applicationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

		val mainActivityHolder = MainActivityHolder(_mainActivity!!)
		val readContactsPermissionController = ReadContactsPermissionController(mainActivityHolder)

		val androidHelper: AndroidHelper = AndroidHelperImpl(
			context = applicationContext,
			applicationScope = applicationScope,
			mainActivityHolder = mainActivityHolder,
		)

		val globalServices = GlobalServices.builder()
			.add(applicationContext, ApplicationContextTag)
			.add(applicationScope, ApplicationScopeTag)
			.add(mainActivityHolder)
			.add(readContactsPermissionController)
			.add(androidHelper)
			.build()

		_mainActivity = null

		return globalServices
	}
}

private const val TagPrefix = "Tag.GlobalServices"

private const val ApplicationContextTag = "$TagPrefix.ApplicationContext"
private const val ApplicationScopeTag = "$TagPrefix.ApplicationScope"


fun ServiceBinder.lookupApplicationContext(): Context {
	return lookup(ApplicationContextTag)
}

fun ServiceBinder.lookupApplicationScope(): CoroutineScope {
	return lookup(ApplicationScopeTag)
}
