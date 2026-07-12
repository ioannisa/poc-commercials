package eu.anifantakis.commercials.core.presentation.design_system.platform

// Web keeps ONE visual profile regardless of the browser's host OS - a
// browser app should look like a (good) web app, not imitate the OS chrome
// around it. Input differences (phone vs desktop browser) are carried by
// InputCapabilities, not by the platform.
internal actual fun detectUiPlatform(): UiPlatform = UiPlatform.WEB
