package eu.anifantakis.poc.ctv

import platform.UIKit.UIDevice
import androidx.compose.runtime.Composable

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

@Composable
actual fun WithTextPrefetch(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    content()
}