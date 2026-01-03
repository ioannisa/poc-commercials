package eu.anifantakis.poc.ctv

import androidx.compose.runtime.Composable

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()

@Composable
actual fun WithTextPrefetch(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    content()
}