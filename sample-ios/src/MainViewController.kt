package sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point for the iOS host: `MainViewControllerKt.MainViewController()`, called from EntryPoint.swift. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
