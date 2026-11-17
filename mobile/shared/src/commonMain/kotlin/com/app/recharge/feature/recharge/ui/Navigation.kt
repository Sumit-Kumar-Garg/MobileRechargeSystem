package com.app.recharge.feature.recharge.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.app.recharge.LocalAppGraph
import kotlinx.serialization.Serializable

@Serializable object NumberKey
@Serializable data class PlansKey(val numberId: String, val market: String, val masked: String)

@Serializable
data class PaymentKey(
    val numberId: String,
    val planId: String,
    val planName: String,
    val amountMinor: Int,
    val currency: String,
    val masked: String,
)

@Serializable data class StatusKey(val orderId: String)

/**
 * number -> plans -> payment -> status, the same on Android and iOS.
 * Each screen's ViewModel is created here from the app graph and lives as long as its place on the back
 * stack, so it survives rotation but is gone once the screen is left.
 */
@Composable
fun AppNavHost(navController: NavHostController) {
    val graph = LocalAppGraph.current
    NavHost(navController, startDestination = NumberKey) {
        composable<NumberKey> {
            NumberRoute(
                onLinked = { number -> navController.navigate(PlansKey(number.id, number.market, number.masked)) },
                viewModel = viewModel { NumberViewModel(graph.repository, graph.recorder, graph.session) },
            )
        }
        composable<PlansKey> { entry ->
            val key = entry.toRoute<PlansKey>()
            PlansRoute(
                masked = key.masked,
                onSelected = { plan ->
                    navController.navigate(
                        PaymentKey(key.numberId, plan.id, plan.name, plan.priceMinor, plan.currency, key.masked),
                    )
                },
                viewModel = viewModel { PlansViewModel(graph.repository, graph.recorder, key.numberId, key.market) },
            )
        }
        composable<PaymentKey> { entry ->
            val key = entry.toRoute<PaymentKey>()
            PaymentRoute(
                planName = key.planName,
                amountMinor = key.amountMinor,
                currency = key.currency,
                masked = key.masked,
                onDone = { orderId ->
                    // Clear plans and payment from the back stack so Back can never lead to a second payment.
                    navController.navigate(StatusKey(orderId)) { popUpTo<NumberKey>() }
                },
                viewModel = viewModel { PaymentViewModel(graph.payForPlan, graph.recorder, key.numberId, key.planId) },
            )
        }
        composable<StatusKey> { entry ->
            val key = entry.toRoute<StatusKey>()
            StatusRoute(
                onNewRecharge = { navController.popBackStack<NumberKey>(inclusive = false) },
                viewModel = viewModel {
                    StatusViewModel(graph.observeOrder, graph.recorder, graph.session, key.orderId)
                },
            )
        }
    }
}
