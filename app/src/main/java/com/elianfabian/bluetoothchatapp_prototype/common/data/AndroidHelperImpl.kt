package com.elianfabian.bluetoothchatapp_prototype.common.data

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsStatusCodes
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class AndroidHelperImpl(
	private val context: Context,
	private val applicationScope: CoroutineScope,
	private val mainActivityHolder: MainActivityHolder,
) : AndroidHelper {

	private val activity: FragmentActivity get() = mainActivityHolder.mainActivity

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

	override suspend fun showMakeDeviceDiscoverableDialog(seconds: Int) = suspendCancellableCoroutine { continuation ->
		require(seconds in 1..300) {
			"Seconds must be between 1 and 300"
		}

		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				continuation.resume(result.resultCode == Activity.RESULT_OK)
			},
		)
		val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
			putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds)
		}

//		context.getSharedPreferences("Bluetooth", Context.MODE_PRIVATE).edit {
//			putInt("discoverable.seconds", seconds)
//			putLong("discoverable.timestamp", System.currentTimeMillis())
//		}

		continuation.invokeOnCancellation {
			launcher.unregister()
		}
		launcher.launch(intent)
	}

//	private fun isDeviceDiscoverable(context: Context): Boolean {
//		val prefs = context.getSharedPreferences("Bluetooth", Context.MODE_PRIVATE)
//		val seconds = prefs.getInt("discoverable.seconds", 0)
//		val timestamp = prefs.getLong("discoverable.timestamp", 0L)
//		if (seconds <= 0) {
//			return false
//		}
//
//		val elapsedSeconds = (System.currentTimeMillis() - timestamp) / 1000
//		return elapsedSeconds < seconds && seconds <= 300
//	}


	override suspend fun showEnableBluetoothDialog(): Boolean = suspendCancellableCoroutine { continuation ->
		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				continuation.resume(result.resultCode == Activity.RESULT_OK)
			},
		)
		continuation.invokeOnCancellation {
			launcher.unregister()
		}
		launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
	}

	override suspend fun showEnableLocationDialog(): Boolean = suspendCancellableCoroutine { continuation ->
		// Source: https://developers.google.com/android/reference/com/google/android/gms/location/SettingsClient

		val locationRequest = LocationRequest.Builder(Long.MAX_VALUE)
			.setPriority(Priority.PRIORITY_LOW_POWER)
			.build()

		val client = LocationServices.getSettingsClient(activity)

		val settingsRequest = LocationSettingsRequest.Builder()
			.addLocationRequest(locationRequest)
			.setAlwaysShow(true)
			.build()

		client.checkLocationSettings(settingsRequest)
			.addOnCompleteListener { task ->
				try {
					@Suppress("unused", "UNUSED_VARIABLE")
					val response = task.getResult(ApiException::class.java)
				}
				catch (exception: ApiException) {
					when (exception.statusCode) {
						LocationSettingsStatusCodes.RESOLUTION_REQUIRED -> {
							try {
								// Cast to a resolvable exception
								val resolvable = exception as ResolvableApiException

								// Show the dialog by calling startResolutionForResult(),
								// and check the result in onActivityResult().
								val launcher = createLauncher(
									contract = ActivityResultContracts.StartIntentSenderForResult(),
									callback = { result ->
										continuation.resume(result.resultCode == Activity.RESULT_OK)
									},
								)

								val intentSenderRequest = IntentSenderRequest.Builder(resolvable.resolution)
									.build()
								launcher.launch(intentSenderRequest)
							}
							catch (e: IntentSender.SendIntentException) {
								// Ignore the error.
							}
							catch (e: ClassCastException) {
								// Ignore, should be an impossible error.
							}
						}
						LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE -> {
							// Location settings are not satisfied. However, we have no way to fix the
							// settings so we won't show the dialog.
							continuation.resume(false)
						}
					}
				}
			}
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


// Some keys throws the following exception when the app is installed from an apk (not from the editor):
// java.lang.SecurityException: Settings key: <bluetooth_name> is only readable to apps with targetSdkVersion lower than or equal to: 31
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
