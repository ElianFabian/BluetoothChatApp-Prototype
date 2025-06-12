package com.elianfabian.bluetoothchatapp_prototype.common.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.elianfabian.bluetoothchatapp_prototype.BluetoothApplication
import com.elianfabian.bluetoothchatapp_prototype.MainActivity
import com.elianfabian.bluetoothchatapp_prototype.R
import com.elianfabian.bluetoothchatapp_prototype.common.data.BluetoothPermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.NotificationController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.allAreGranted
import com.elianfabian.bluetoothchatapp_prototype.common.util.contentToString
import com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.forEachServiceOfType
import com.zhuinden.simplestackextensions.servicesktx.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BluetoothService : Service() {

//	private val _scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

	inner class BluetoothBinder : Binder() {
		fun getService() = this@BluetoothService
	}

	override fun onBind(intent: Intent): IBinder? {
		return BluetoothBinder()
	}



	override fun onCreate() {
		super.onCreate()

		println("$$$ BluetoothService.onCreate")

//		val globalServices = (application as BluetoothApplication).globalServicesProvider.getOrNull() ?: return
//		val bluetoothPermissionController = globalServices.get<BluetoothPermissionController>()
//
////		val bluetoothController = globalBackstack.lookup<BluetoothController>()
////
////		_scope.launch {
////			println("$$$ BluetoothService start of collect")
////			bluetoothController.devices
////				.map { devices ->
////					devices.filter { it.connectionState == BluetoothDevice.ConnectionState.Connecting }
////				}
////				.collect { devices ->
////					devices.forEach { device ->
////						bluetoothController.listenMessagesFrom(device.address).collect { message ->
////							println("$$$ BluetoothService. from: '${message.senderName}': ${message.content}")
////						}
////					}
////				}
////			println("$$$ BluetoothService end of collect")
////		}
//
//		val notificationManager = getSystemService<NotificationManager>() ?: return
//
//		if (Build.VERSION.SDK_INT >= 26) {
//			val notificationChannel = NotificationChannel(
//				NotificationController.Channel.Main,
//				"Main",
//				NotificationManager.IMPORTANCE_HIGH,
//			)
//
//			notificationManager.createNotificationChannel(notificationChannel)
//		}
//
//		_scope.launch {
//			bluetoothPermissionController.state.first { permissionsState ->
//				permissionsState.allAreGranted
//			}
//
//			println("$$$ startForeground ${bluetoothPermissionController.state.value}")
//			startForeground(1, notification)
//		}
	}

//	override fun onTaskRemoved(rootIntent: Intent?) {
//		super.onTaskRemoved(rootIntent)
//
//		println("$$$ onTaskRemoved: rootIntent = ${rootIntent.contentToString()}")
//	}

//	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//		if (intent?.action == "stop") {
//			println("$$$ stop service")
//			stopForeground(STOP_FOREGROUND_REMOVE)
//			stopSelf()
//			Process.killProcess(Process.myPid())
//		}
//
//		return super.onStartCommand(intent, flags, startId)
//	}


//	override fun onDestroy() {
//		super.onDestroy()
//
//		println("$$$ BluetoothService.onDestroy")
//
//		_scope.cancel()
//	}
}
