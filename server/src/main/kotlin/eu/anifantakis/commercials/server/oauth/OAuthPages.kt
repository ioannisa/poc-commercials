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
    button:disabled { background: #c3ccd8; cursor: default; }
    .btn-spinner { display: none; width: 15px; height: 15px; box-sizing: border-box;
                   border: 2px solid rgba(255,255,255,.45); border-top-color: #ffffff;
                   border-radius: 50%; margin-right: 8px; vertical-align: -2px;
                   animation: cm-spin .8s linear infinite; }
    button.busy .btn-spinner { display: inline-block; }
    @keyframes cm-spin { to { transform: rotate(360deg); } }
    .otp-row { display: none; gap: 8px; justify-content: space-between; margin-top: 4px; }
    .otp-digit { flex: 1; min-width: 0; height: 60px; text-align: center; font-size: 34px;
                 font-weight: 700; border: 1px solid #c3ccd8; border-radius: 8px; background: #fbfcfe; }
    .otp-digit:focus { border-color: #2563eb; outline: none; }
"""

/**
 * Arms a form's submit button: on submit it greys out, shows the inline
 * spinner, and refuses further clicks. The disable is deferred a tick so the
 * in-flight submission still carries every field.
 */
private fun busyButtonScript(formId: String, buttonId: String): String = """
    <script>
    (function () {
      var form = document.getElementById('$formId');
      var btn = document.getElementById('$buttonId');
      if (!form || !btn) return;
      form.addEventListener('submit', function (ev) {
        if (btn.classList.contains('busy')) { ev.preventDefault(); return; }
        btn.classList.add('busy');
        setTimeout(function () { btn.disabled = true; }, 0);
      });
    })();
    </script>
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
        <title>Authorize $safeClient - Commercials Manager AI</title>
        <style>$PAGE_STYLE</style>
        </head>
        <body>
        <main class="card">
        <h1>Authorize <span class="client">$safeClient</span></h1>
        <p class="app">Commercials Manager AI</p>
        $errorBox
        <form method="post" action="/oauth/authorize" id="auth-form">
            $hiddenInputs
            <label for="username">Username</label>
            <input type="text" id="username" name="username" autocomplete="username" autofocus required>
            <label for="password">Password</label>
            <input type="password" id="password" name="password" autocomplete="current-password" required>
            <label for="connected_account">Your $safeClient account (e-mail)</label>
            <input type="text" id="connected_account" name="connected_account" autocomplete="email"
                   placeholder="e.g. you@example.com" maxlength="255" required>
            <button type="submit" id="auth-submit"><span class="btn-spinner"></span>Sign in &amp; authorize</button>
        </form>
        <p class="note">Signing in grants <span class="client">$safeClient</span> access to the
        Commercials Manager data your account can see, until you revoke it. The account e-mail
        you enter is recorded with this connection so administrators can tell your connections
        apart. If you did not initiate this request, close this page.</p>
        ${busyButtonScript("auth-form", "auth-submit")}
        </main>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Step 2 of the consent: the OTP sent to the declared e-mail. Carries ONLY the
 * opaque consent id - every OAuth param lives server-side on the consent row.
 */
fun renderConsentOtpPage(
    clientName: String,
    consentId: String,
    maskedEmail: String,
    ttlMinutes: Int,
    error: String? = null,
): String {
    val safeClient = htmlEscape(clientName)
    val errorBox = error?.let { """<div class="error">${htmlEscape(it)}</div>""" } ?: ""
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Verify - Commercials Manager</title>
        <style>$PAGE_STYLE</style>
        </head>
        <body>
        <main class="card">
        <h1>Check your e-mail</h1>
        <p class="app">Commercials Manager</p>
        $errorBox
        <p>We sent a verification code to <span class="client">${htmlEscape(maskedEmail)}</span>.
        Enter it to finish connecting <span class="client">$safeClient</span>.</p>
        <form method="post" action="/oauth/authorize/otp" id="otp-form">
            <input type="hidden" name="consent_id" value="${htmlEscape(consentId)}">
            <label for="otp">Verification code</label>
            <input type="text" id="otp" name="otp" inputmode="numeric" autocomplete="one-time-code"
                   pattern="[0-9]*" maxlength="8" autofocus required>
            <div class="otp-row" id="otp-row"></div>
            <button type="submit" id="otp-submit"><span class="btn-spinner"></span>Verify &amp; authorize</button>
        </form>
        <p class="note">The code is valid for $ttlMinutes minutes. If it never arrives, close this
        page and start the connection again from your AI client.</p>
        <script>
        // Progressive enhancement: swap the plain code input for six digit
        // boxes (auto-advance, backspace-to-previous, paste-distributes) and
        // hold the CTA disabled until all six are filled. Without JS the
        // plain input above still submits.
        (function () {
          var input = document.getElementById('otp');
          var row = document.getElementById('otp-row');
          var btn = document.getElementById('otp-submit');
          if (!input || !row || !btn) return;
          input.type = 'hidden'; input.required = false; input.removeAttribute('autofocus');
          var boxes = [];
          function sync() {
            var v = boxes.map(function (b) { return b.value; }).join('');
            input.value = v;
            if (!btn.classList.contains('busy')) btn.disabled = v.length !== 6;
          }
          function handleInput(i) {
            var digits = boxes[i].value.replace(/[^0-9]/g, '');
            if (digits.length > 1) {
              for (var j = 0; i + j < boxes.length && j < digits.length; j++) boxes[i + j].value = digits[j];
              boxes[Math.min(i + digits.length, boxes.length - 1)].focus();
            } else {
              boxes[i].value = digits;
              if (digits && i < boxes.length - 1) boxes[i + 1].focus();
            }
            sync();
          }
          for (var i = 0; i < 6; i++) {
            (function (idx) {
              var b = document.createElement('input');
              b.type = 'text'; b.inputMode = 'numeric';
              b.autocomplete = idx === 0 ? 'one-time-code' : 'off';
              b.className = 'otp-digit'; b.maxLength = 6;
              b.addEventListener('input', function () { handleInput(idx); });
              b.addEventListener('keydown', function (ev) {
                if (ev.key === 'Backspace' && !b.value && idx > 0) boxes[idx - 1].focus();
              });
              boxes.push(b); row.appendChild(b);
            })(i);
          }
          row.style.display = 'flex';
          btn.disabled = true;
          boxes[0].focus();
        })();
        </script>
        ${busyButtonScript("otp-form", "otp-submit")}
        </main>
        </body>
        </html>
    """.trimIndent()
}

/** Terminal result of the e-mail approval link (no redirect target exists here). */
fun renderApprovalResultPage(approved: Boolean): String {
    val title = if (approved) "Connection approved" else "Link no longer valid"
    val message = if (approved) {
        "The AI connection on your account is now active. You can close this page."
    } else {
        "This approval link was already used, expired, or the connection was revoked. " +
            "If your AI client still cannot connect, contact your administrator."
    }
    return renderOAuthErrorPage(title, message)
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
