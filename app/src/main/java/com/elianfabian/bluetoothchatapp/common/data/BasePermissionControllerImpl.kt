@file:SuppressLint("InlinedApi")

package com.elianfabian.bluetoothchatapp.common.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.elianfabian.bluetoothchatapp.common.domain.MultiplePermissionController
import com.elianfabian.bluetoothchatapp.common.domain.PermissionController
import com.elianfabian.bluetoothchatapp.common.domain.PermissionState
import com.elianfabian.bluetoothchatapp.common.util.simplestack.callbacks.ApplicationBackgroundStateChangeCallback
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

abstract class BasePermissionControllerImpl(
	private val mainActivityHolder: MainActivityHolder,
) : PermissionController,
	ApplicationBackgroundStateChangeCallback,
	ScopedServices.Registered {

	abstract val permissionName: String

	private val _state by lazy { MutableStateFlow(getCurrentState()) }
	override val state = _state.asStateFlow()

	private val _launcher: ActivityResultLauncher<String> by lazy {
		mainActivityHolder.mainActivity.activityResultRegistry.register(
			key = UUID.randomUUID().toString(),
			contract = ActivityResultContracts.RequestPermission(),
			callback = {
				val result = getCurrentState()
				_state.value = result
				_resultContinuation?.resume(result)
				_resultContinuation = null
			},
		)
	}

	private var _resultContinuation: CancellableContinuation<PermissionState>? = null


	override fun request() {
		_launcher.launch(permissionName)
	}

	override suspend fun awaitResult(): PermissionState {
		if (getCurrentState() == PermissionState.Granted) {
			return PermissionState.Granted
		}
		check(_resultContinuation == null) {
			"Already waiting for a result"
		}
		return suspendCancellableCoroutine { continuation ->
			_resultContinuation = continuation
		}
	}


	@SuppressLint("ObsoleteSdkInt")
	protected open fun getCurrentState(): PermissionState {
		return getPermissionState(
			activity = mainActivityHolder.mainActivity,
			permissionName = permissionName,
		)
	}

	override fun onAppEnteredBackground() = Unit

	override fun onAppEnteredForeground() {
		_state.value = getCurrentState()
	}

	override fun onServiceRegistered() {
		// no-op
	}

	override fun onServiceUnregistered() {
		_launcher.unregister()
		_resultContinuation?.cancel()
		_resultContinuation = null
	}
}

abstract class BaseMultiplePermissionControllerImpl(
	private val mainActivityHolder: MainActivityHolder,
) : MultiplePermissionController,
	ApplicationBackgroundStateChangeCallback,
	ScopedServices.Registered {

	abstract val permissionNames: List<String>

	private val _state by lazy { MutableStateFlow(getCurrentState()) }
	override val state = _state.asStateFlow()

	private val _launcher: ActivityResultLauncher<Array<String>> by lazy {
		mainActivityHolder.mainActivity.activityResultRegistry.register(
			key = UUID.randomUUID().toString(),
			contract = ActivityResultContracts.RequestMultiplePermissions(),
			callback = {
				val result = getCurrentState()
				_state.value = result
				_resultContinuation?.resume(result)
				_resultContinuation = null
			},
		)
	}

	private var _resultContinuation: CancellableContinuation<Map<String, PermissionState>>? = null


	override fun request() {
		_launcher.launch(permissionNames.toTypedArray())
	}

	override suspend fun awaitResult(): Map<String, PermissionState> {
		if (getCurrentState().all { it.value == PermissionState.Granted }) {
			return getCurrentState()
		}
		check(_resultContinuation == null) {
			"Already waiting for a result"
		}
		return suspendCancellableCoroutine { continuation ->
			_resultContinuation = continuation
		}
	}

	@SuppressLint("ObsoleteSdkInt")
	protected open fun getCurrentState(): Map<String, PermissionState> {
		return permissionNames.associateWith { name ->
			getPermissionState(
				activity = mainActivityHolder.mainActivity,
				permissionName = name,
			)
		}
	}

	override fun onAppEnteredBackground() = Unit

	override fun onAppEnteredForeground() {
		_state.value = getCurrentState()
	}

	override fun onServiceRegistered() {
		// no-op
	}

	override fun onServiceUnregistered() {
		_launcher.unregister()
		_resultContinuation?.cancel()
		_resultContinuation = null
	}
}

@SuppressLint("ObsoleteSdkInt")
private fun getPermissionState(
	activity: Activity,
	permissionName: String,
): PermissionState {
	val sharedPrefs = activity.getSharedPreferences("permissions", Context.MODE_PRIVATE)

	return if (ContextCompat.checkSelfPermission(
			activity,
			permissionName,
		) == PackageManager.PERMISSION_GRANTED
	) {
		if (Build.VERSION.SDK_INT >= 23) {
			sharedPrefs.edit { putBoolean(permissionName, true) }
		}
		PermissionState.Granted
	}
	else {
		if (Build.VERSION.SDK_INT >= 23) {
			if (activity.shouldShowRequestPermissionRationale(permissionName)) {
				sharedPrefs.edit { putBoolean(permissionName, true) }
				PermissionState.Denied
			}
			else {
				if (!sharedPrefs.getBoolean(permissionName, false)) {
					PermissionState.NotDetermined
				}
				else PermissionState.PermanentlyDenied
			}
		}
		else {
			if (!sharedPrefs.getBoolean(permissionName, false)) {
				PermissionState.NotDetermined
			}
			else PermissionState.Denied
		}
	}
}
