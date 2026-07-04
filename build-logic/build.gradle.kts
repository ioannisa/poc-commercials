plugins {
    `kotlin-dsl`
}

// Plugin marker artifacts so the precompiled convention scripts can apply
// them by id. Versions come from the shared catalog.
fun plugin(provider: Provider<PluginDependency>) =
    provider.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(plugin(libs.plugins.kotlinMultiplatform))
    implementation(plugin(libs.plugins.androidKmpLibrary))
    implementation(plugin(libs.plugins.composeMultiplatform))
    implementation(plugin(libs.plugins.composeCompiler))
    implementation(plugin(libs.plugins.kotlinSerialization))
}
