package com.app.recharge

import androidx.compose.ui.window.ComposeUIViewController
import com.app.recharge.platform.IosPlatform
import platform.UIKit.UIViewController

private var graph: AppGraph? = null

/**
 * The iOS entry point, called from Swift (ContentView.swift). It builds the app's objects once and shows
 * the same Compose UI that Android shows.
 */
fun MainViewController(baseUrl: String, appVersion: String): UIViewController {
    val appGraph = graph ?: AppGraph(IosPlatform(), AppConfig(baseUrl, appVersion)).also { graph = it }
    return ComposeUIViewController { RechargeApp(appGraph) }
}
