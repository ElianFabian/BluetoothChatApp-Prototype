package com.elianfabian.bluetoothchatapp_prototype.home.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.FragmentKey
import com.elianfabian.bluetoothchatapp_prototype.common.util.simplestack.compose.ComposeKeyedFragment
import com.elianfabian.bluetoothchatapp_prototype.home.di.HomeModule
import com.elianfabian.bluetoothchatapp_prototype.home.presentation.components.DeviceScreen
import com.zhuinden.simplestackcomposeintegration.services.rememberService
import kotlinx.parcelize.Parcelize

@Parcelize
data object HomeKey : FragmentKey(
	serviceModule = HomeModule,
) {
	override fun instantiateFragment() = HomeFragment()

	class HomeFragment : ComposeKeyedFragment() {

		@Composable
		override fun Content(innerPadding: PaddingValues) {

			val viewModel = rememberService<HomeViewModel>()

			val state by viewModel.state.collectAsState()

			DeviceScreen(
				state = state,
				onAction = { action ->
					viewModel.sendAction(action)
				}
			)
		}
	}
}
