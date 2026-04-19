package eu.anifantakis.poc.ctv.db

// 10.0.2.2 is the special loopback address the Android emulator uses to reach the host.
// On a physical device, change this to the dev machine's LAN IP.
actual fun dbServerBaseUrl(): String = "http://10.0.2.2:8080"