package eu.anifantakis.commercials.core.data.network

/**
 * Thrown when the server rejects our bearer token (401). By the time it is
 * raised the session has been cleared (see [ApiHttpClient]), so the UI's
 * session-revision observer bounces the user back to the login screen;
 * [dataCall]/[remoteCall] map it to UNAUTHORIZED.
 */
class SessionExpiredException : Exception("Session expired - please sign in again")
