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
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import com.elianfabian.bluetoothchatapp.common.domain.AndroidHelper
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.zhuinden.simplestack.Bundleable
import com.zhuinden.statebundle.StateBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class AndroidHelperImpl(
	private val context: Context,
	private val applicationScope: CoroutineScope,
	private val mainActivityHolder: MainActivityHolder,
) : AndroidHelper {

	private val activity: FragmentActivity get() = mainActivityHolder.mainActivity

	override val bluetoothName by settingFlow(
		context = context,
		key = "bluetooth_name",
		scope = applicationScope,
		type = SettingFlowDelegate.Type.Secure,
	)

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
		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				callback(result.resultCode == Activity.RESULT_OK)
			},
		)
		val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
			putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
		}
		launcher.launch(intent)
	}

	override fun showEnableBluetoothDialog(callback: (enabled: Boolean) -> Unit) {
		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				callback(result.resultCode == Activity.RESULT_OK)
			},
		)

		launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
	}

	override fun closeKeyboard() {
		activity.currentFocus?.also { focus ->
			val inputMethodManager = context.getSystemService<InputMethodManager>() ?: return
			inputMethodManager.hideSoftInputFromWindow(focus.windowToken, 0)
		}
	}


	private fun <I, O> createLauncher(
		contract: ActivityResultContract<I, O>,
		callback: (result: O) -> Unit,
	): ActivityResultLauncher<I> {
		var cleanerCallback: (() -> Unit)? = null

		val launcher = activity.activityResultRegistry.register(
			key = generateLauncherKey(),
			contract = contract,
			callback = { result ->
				callback(result)
				cleanerCallback?.invoke()
				cleanerCallback = null
			}
		)

		cleanerCallback = {
			launcher.unregister()
		}

		return launcher
	}

	// This only needs to persist across configuration changes
	private fun generateLauncherKey() = UUID.randomUUID().toString()

	private fun isPermissionGranted(permission: String): Boolean {
		return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
	}
}


private class SettingFlowDelegate(
	private val context: Context,
	private val key: String,
	private val scope: CoroutineScope,
	private val type: Type,
) : ReadOnlyProperty<Any?, StateFlow<String>> {

	private val flow by lazy {
		callbackFlow {
			val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
				override fun onChange(selfChange: Boolean, uri: Uri?) {
					val value = when (type) {
						Type.Global -> Settings.Global.getString(context.contentResolver, key)
						Type.System -> Settings.System.getString(context.contentResolver, key)
						Type.Secure -> Settings.Secure.getString(context.contentResolver, key)
					}
					if (value != null) {
						trySend(value)
					}
				}
			}

			val uri = when (type) {
				Type.Global -> Settings.Global.getUriFor(key)
				Type.System -> Settings.System.getUriFor(key)
				Type.Secure -> Settings.Secure.getUriFor(key)
			}
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

	enum class Type {
		Global,
		System,
		Secure,
	}
}

private fun settingFlow(
	context: Context,
	key: String,
	scope: CoroutineScope,
	type: SettingFlowDelegate.Type,
): ReadOnlyProperty<Any?, StateFlow<String>> {
	return SettingFlowDelegate(context, key, scope, type)
}
