package eu.anifantakis.commercials.server.oauth

/**
 * Server-rendered HTML for the OAuth authorize flow - hand-built strings in
 * the mailer's inline-style idiom (the codebase has no HTML-builder plugin,
 * and two small pages do not justify one). ENGLISH ONLY by decision.
 *
 * SECURITY: everything echoed into the page is HTML-escaped. `client_name`
 * comes from OPEN dynamic client registration, i.e. it is attacker-controlled
 * text; the hidden OAuth params are caller-supplied query values. [htmlEscape]
 * covers both element content and double-quoted attribute values.
 */

internal fun htmlEscape(s: String): String = buildString(s.length) {
    for (ch in s) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

private const val PAGE_STYLE = """
    body { margin: 0; font-family: -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
           background: #f2f4f7; color: #1c2430; display: flex; min-height: 100vh;
           align-items: center; justify-content: center; }
    .card { background: #ffffff; border: 1px solid #d9dee6; border-radius: 10px;
            padding: 32px 36px; max-width: 420px; width: 100%; margin: 24px;
            box-shadow: 0 4px 16px rgba(28, 36, 48, 0.08); }
    h1 { font-size: 20px; margin: 0 0 6px 0; }
    .app { color: #5a6a80; font-size: 13px; margin: 0 0 22px 0; }
    .client { font-weight: 600; }
    label { display: block; font-size: 13px; color: #37445a; margin: 14px 0 4px 2px; }
    input[type=text], input[type=password] {
        width: 100%; box-sizing: border-box; padding: 10px 12px; font-size: 15px;
        border: 1px solid #c3ccd8; border-radius: 6px; background: #fbfcfe; }
    button { width: 100%; margin-top: 22px; padding: 11px 0; font-size: 15px; font-weight: 600;
             color: #ffffff; background: #2563eb; border: none; border-radius: 6px; cursor: pointer; }
    button:hover { background: #1d4fd8; }
    .error { background: #fdecec; border: 1px solid #f5b5b5; color: #9b1c1c;
             border-radius: 6px; padding: 10px 12px; font-size: 13px; margin: 0 0 14px 0; }
    .note { color: #5a6a80; font-size: 12px; margin-top: 18px; line-height: 1.5; }
"""

/**
 * The combined login + consent page. The user proves who they are with their
 * Commercials Manager credentials AND, by submitting, authorizes [clientName]
 * - one page, every time, for every dynamically registered client (auto-skip
 * keyed on a client_id anyone can mint at /oauth/register would be an
 * account-takeover primitive).
 *
 * [hidden] carries the validated OAuth params through the POST - the handler
 * re-validates every one of them from scratch (hidden fields are still client
 * input).
 */
fun renderAuthorizePage(
    clientName: String,
    hidden: Map<String, String>,
    error: String? = null,
): String {
    val safeClient = htmlEscape(clientName)
    val hiddenInputs = hidden.entries.joinToString("\n            ") { (name, value) ->
        """<input type="hidden" name="${htmlEscape(name)}" value="${htmlEscape(value)}">"""
    }
    val errorBox = error?.let { """<div class="error">${htmlEscape(it)}</div>""" } ?: ""
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Authorize $safeClient - Commercials Manager</title>
        <style>$PAGE_STYLE</style>
        </head>
        <body>
        <main class="card">
        <h1>Authorize <span class="client">$safeClient</span></h1>
        <p class="app">Commercials Manager</p>
        $errorBox
        <form method="post" action="/oauth/authorize">
            $hiddenInputs
            <label for="username">Username</label>
            <input type="text" id="username" name="username" autocomplete="username" autofocus required>
            <label for="password">Password</label>
            <input type="password" id="password" name="password" autocomplete="current-password" required>
            <button type="submit">Sign in &amp; authorize</button>
        </form>
        <p class="note">Signing in grants <span class="client">$safeClient</span> access to the
        Commercials Manager data your account can see, until you revoke it. If you did not
        initiate this request, close this page.</p>
        </main>
        </body>
        </html>
    """.trimIndent()
}

/** A terminal error page - used where redirecting back would be unsafe (bad client/redirect). */
fun renderOAuthErrorPage(title: String, message: String): String = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${htmlEscape(title)} - Commercials Manager</title>
    <style>$PAGE_STYLE</style>
    </head>
    <body>
    <main class="card">
    <h1>${htmlEscape(title)}</h1>
    <p class="app">Commercials Manager</p>
    <p>${htmlEscape(message)}</p>
    </main>
    </body>
    </html>
""".trimIndent()
