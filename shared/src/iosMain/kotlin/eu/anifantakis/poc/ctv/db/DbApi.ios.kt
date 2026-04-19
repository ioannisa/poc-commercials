package eu.anifantakis.poc.ctv.db

// iOS simulator shares the host's network stack, so localhost works.
// For a physical iPhone, change this to the dev machine's LAN IP.
actual fun dbServerBaseUrl(): String = "http://localhost:8080"