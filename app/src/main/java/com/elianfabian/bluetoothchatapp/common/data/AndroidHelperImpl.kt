package com.elianfabian.bluetoothchatapp.common.data

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.elianfabian.bluetoothchatapp.common.domain.AndroidHelper
import androidx.core.net.toUri
import com.zhuinden.simplestack.Bundleable
import com.zhuinden.statebundle.StateBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class AndroidHelperImpl(
	private val context: Context,
	private val applicationScope: CoroutineScope,
	private val mainActivityHolder: MainActivityHolder,
) : AndroidHelper,
	Bundleable {

	override val bluetoothName by secureSettingFlow(
		context = context,
		key = "bluetooth_name",
		scope = applicationScope,
	)

	private var _makeDeviceDiscoverableLauncherKey: String = UUID.randomUUID().toString()
	private var _enableBluetoothLauncherKey: String = UUID.randomUUID().toString()


	override fun showToast(message: String) {
		Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
	}

	private fun openNotificationSettings() {
		val intent = Intent().apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

			action = if (Build.VERSION.SDK_INT >= 26) {
				Settings.ACTION_APP_NOTIFICATION_SETTINGS
			}
			else Settings.ACTION_APPLICATION_DETAILS_SETTINGS

			if (Build.VERSION.SDK_INT >= 26) {
				putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
			}
			else {
				data = "package:${context.packageName}".toUri()
			}
		}
		context.startActivity(intent)
	}

	override fun openAppSettings() {
		val intent = Intent().apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
			data = "package:${context.packageName}".toUri()
		}
		context.startActivity(intent)
	}

	override fun openBluetoothSettings() {
		val intent = Intent().apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			action = Settings.ACTION_BLUETOOTH_SETTINGS
		}
		context.startActivity(intent)
	}

	override fun openDeviceInfoSettings() {
		val intent = Intent().apply {
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			action = Settings.ACTION_DEVICE_INFO_SETTINGS
		}
		context.startActivity(intent)
	}

	override fun makeDeviceDiscoverable(callback: (accepted: Boolean) -> Unit) {
		var cleanerCallback: (() -> Unit)? = null

		val activity = mainActivityHolder.mainActivity
		val launcher = activity.activityResultRegistry.register(
			key = _makeDeviceDiscoverableLauncherKey,
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				val accepted = result.resultCode == Activity.RESULT_OK
				callback(accepted)
				cleanerCallback?.invoke()
				cleanerCallback = null
			}
		)

		val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
			putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
		}

		launcher.launch(intent)

		cleanerCallback = {
			launcher.unregister()
		}
	}

	override fun showEnableBluetoothDialog(callback: (enabled: Boolean) -> Unit) {
		var cleanerCallback: (() -> Unit)? = null
		val launcher = mainActivityHolder.mainActivity.activityResultRegistry.register(
			key = _enableBluetoothLauncherKey,
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				callback(result.resultCode == Activity.RESULT_OK)
				cleanerCallback?.invoke()
				cleanerCallback = null
			}
		)
		launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

		cleanerCallback = {
			launcher.unregister()
		}
	}

	override fun fromBundle(bundle: StateBundle?) {
		val stateBundle = bundle ?: return
		_makeDeviceDiscoverableLauncherKey = stateBundle.getString(
			"_makeDeviceDiscoverableLauncherKey",
			UUID.randomUUID().toString(),
		)
		_enableBluetoothLauncherKey = stateBundle.getString(
			"_enableBluetoothLauncherKey",
			UUID.randomUUID().toString(),
		)
	}

	override fun toBundle(): StateBundle {
		return StateBundle().apply {
			putString("_makeDeviceDiscoverableLauncherKey", _makeDeviceDiscoverableLauncherKey)
			putString("_enableBluetoothLauncherKey", _enableBluetoothLauncherKey)
		}
	}

	private fun isPermissionGranted(permission: String): Boolean {
		return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
	}
}


private class SecureSettingFlowDelegate(
	private val context: Context,
	private val key: String,
	private val scope: CoroutineScope,
) : ReadOnlyProperty<Any?, StateFlow<String>> {

	private val flow by lazy {
		callbackFlow {
			val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
				override fun onChange(selfChange: Boolean, uri: Uri?) {
					val value = Settings.Secure.getString(context.contentResolver, key)
					if (value != null) trySend(value)
				}
			}

			val uri = Settings.Secure.getUriFor(key)
			context.contentResolver.registerContentObserver(uri, false, observer)

			awaitClose {
				context.contentResolver.unregisterContentObserver(observer)
			}
		}.stateIn(
			scope = scope,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = Settings.Secure.getString(context.contentResolver, key) ?: "",
		)
	}

	override fun getValue(thisRef: Any?, property: KProperty<*>): StateFlow<String> = flow
}

private fun secureSettingFlow(
	context: Context,
	key: String,
	scope: CoroutineScope,
): ReadOnlyProperty<Any?, StateFlow<String>> {
	return SecureSettingFlowDelegate(context, key, scope)
}
