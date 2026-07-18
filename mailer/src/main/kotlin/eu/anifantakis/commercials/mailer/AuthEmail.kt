package eu.anifantakis.commercials.mailer

/**
 * Auth emails: the password-reset code and the temp-password notice. Plain,
 * self-contained inline-styled HTML (mail clients ignore <style>/external CSS),
 * in Greek to match the schedule emails. Deliberately minimal - each carries a
 * single credential, so the less chrome the better.
 */

private const val AUTH_EMAIL_HEAD =
    "<!DOCTYPE html><html lang=\"el\"><head><meta charset=\"utf-8\"></head>" +
        "<body style=\"margin:0;padding:24px;background:#F4F5F7;font-family:Arial,Helvetica,sans-serif;color:#1A1A1A;\">"

private const val AUTH_EMAIL_FOOT = "</body></html>"

private fun card(inner: String): String =
    "<div style=\"max-width:440px;margin:0 auto;background:#FFFFFF;border-radius:12px;padding:28px;\">$inner</div>"

private fun codeBox(value: String, big: Boolean): String {
    val size = if (big) "34px;letter-spacing:8px" else "22px;word-break:break-all"
    return "<div style=\"font-family:'Courier New',monospace;font-size:$size;font-weight:bold;" +
        "text-align:center;background:#F0F2F5;border-radius:8px;padding:16px;margin:0 0 16px;\">$value</div>"
}

/** The 6-digit reset code, large and monospaced, plus how long it lasts. */
fun renderPasswordResetEmail(orgName: String, code: String, ttlMinutes: Int): String =
    AUTH_EMAIL_HEAD + card(
        emailMasthead(orgName) +
            "<h2 style=\"margin:0 0 12px;font-size:20px;\">Επαναφορά κωδικού πρόσβασης</h2>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;\">" +
            "Ζητήσατε επαναφορά του κωδικού σας. Εισαγάγετε τον παρακάτω κωδικό στην εφαρμογή:</p>" +
            codeBox(code, big = true) +
            "<p style=\"margin:0 0 8px;font-size:13px;color:#555;\">Ισχύει για $ttlMinutes λεπτά και χρησιμοποιείται μία φορά.</p>" +
            "<p style=\"margin:0;font-size:13px;color:#555;\">Αν δεν το ζητήσατε εσείς, αγνοήστε αυτό το μήνυμα - " +
            "ο κωδικός σας δεν άλλαξε.</p>"
    ) + AUTH_EMAIL_FOOT

/** clientName comes from OPEN dynamic client registration - attacker-controlled text. */
private fun escape(s: String): String = buildString(s.length) {
    for (ch in s) when (ch) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(ch)
    }
}

/**
 * The 6-digit consent verification code, sent to the DECLARED AI-account
 * mailbox during the OAuth consent flow. English: it accompanies the consent
 * page, which is English-only by decision.
 */
fun renderConsentOtpEmail(orgName: String, code: String, clientName: String, ttlMinutes: Int): String =
    AUTH_EMAIL_HEAD + card(
        emailMasthead(orgName) +
            "<h2 style=\"margin:0 0 12px;font-size:20px;\">Verification code</h2>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;\">" +
            "You are connecting <b>${escape(clientName)}</b> to Commercials Manager. " +
            "Enter this code on the authorization page:</p>" +
            codeBox(code, big = true) +
            "<p style=\"margin:0 0 8px;font-size:13px;color:#555;\">Valid for $ttlMinutes minutes, single use.</p>" +
            "<p style=\"margin:0;font-size:13px;color:#555;\">If you did not initiate this, ignore this message - " +
            "nothing was connected.</p>"
    ) + AUTH_EMAIL_FOOT

/**
 * New-AI-connection approval request, sent to the app user's REGISTERED email
 * (Greek, like every account email) when they have opted into connection
 * confirmation. Doubles as the new-connection alert: it names the client, the
 * declared account, and the consent IP/browser, and the grant stays inactive
 * until the link is clicked (or an admin approves).
 */
fun renderConnectionApprovalEmail(
    orgName: String,
    clientName: String,
    connectedAccount: String,
    ip: String?,
    userAgent: String?,
    approveUrl: String,
): String {
    val details = listOfNotNull(
        "Εφαρμογή: <b>${escape(clientName)}</b>",
        "Δηλωμένος λογαριασμός: <b>${escape(connectedAccount)}</b>",
        ip?.let { "IP: <b>${escape(it)}</b>" },
        userAgent?.let { "Πρόγραμμα: <b>${escape(it)}</b>" },
    ).joinToString("<br>")
    return AUTH_EMAIL_HEAD + card(
        emailMasthead(orgName) +
            "<h2 style=\"margin:0 0 12px;font-size:20px;\">Νέα σύνδεση AI στον λογαριασμό σας</h2>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;\">" +
            "Ζητήθηκε σύνδεση ενός AI client στον λογαριασμό σας. Η σύνδεση παραμένει " +
            "<b>ανενεργή</b> μέχρι να την εγκρίνετε:</p>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.7;\">$details</p>" +
            "<div style=\"text-align:center;margin:0 0 16px;\">" +
            "<a href=\"${escape(approveUrl)}\" style=\"display:inline-block;background:#2563eb;color:#ffffff;" +
            "text-decoration:none;font-weight:bold;font-size:15px;padding:12px 28px;border-radius:8px;\">" +
            "Έγκριση σύνδεσης</a></div>" +
            "<p style=\"margin:0;font-size:13px;color:#555;\">Αν δεν την αναγνωρίζετε, ΜΗΝ πατήσετε τον σύνδεσμο - " +
            "αλλάξτε τον κωδικό σας και ενημερώστε τον διαχειριστή.</p>"
    ) + AUTH_EMAIL_FOOT
}

/**
 * A monospaced address box (a URL, shown verbatim so it can be copied).
 */
private fun urlBox(value: String): String =
    "<div style=\"font-family:'Courier New',monospace;font-size:14px;font-weight:bold;" +
        "word-break:break-all;background:#F0F2F5;border-radius:8px;padding:12px 14px;margin:0 0 14px;\">" +
        "${escape(value)}</div>"

/**
 * "How to connect" block for a fresh account, built ONLY from the values the
 * installation actually exposes in server.yaml: the web login URL from
 * `publicBaseUrl`, and the MCP connector URL (`https://<host>/mcp/http`) from
 * the first `mcpAllowedHosts` entry. Either half is omitted when its config is
 * absent, and the whole block disappears when neither is set - a purely local
 * install sends the plain account notice, unchanged.
 */
private fun connectionGuide(webUrl: String?, mcpUrl: String?): String {
    if (webUrl == null && mcpUrl == null) return ""
    val sb = StringBuilder()
    sb.append("<div style=\"border-top:1px solid #E6E8EB;margin-top:22px;padding-top:16px;\">")
    sb.append("<h3 style=\"margin:0 0 10px;font-size:16px;\">Πρόσβαση στην εφαρμογή</h3>")
    if (webUrl != null) {
        sb.append("<p style=\"margin:0 0 8px;font-size:14px;line-height:1.5;\">Συνδεθείτε από τον browser σας στη διεύθυνση:</p>")
        sb.append(
            "<div style=\"margin:0 0 14px;\"><a href=\"${escape(webUrl)}\" " +
                "style=\"display:inline-block;background:$BRAND_NAVY;color:#FFFFFF;text-decoration:none;" +
                "font-weight:bold;font-size:15px;padding:11px 24px;border-radius:8px;\">${escape(webUrl)}</a></div>"
        )
    }
    if (mcpUrl != null) {
        sb.append(
            "<p style=\"margin:0 0 8px;font-size:14px;line-height:1.5;\">Για σύνδεση με AI βοηθό " +
                "(Claude, ChatGPT, Gemini) προσθέστε στην εφαρμογή του έναν <b>custom connector (MCP)</b> " +
                "με την παρακάτω διεύθυνση - θα σας ζητηθεί να συνδεθείτε με τον λογαριασμό σας:</p>"
        )
        sb.append(urlBox(mcpUrl))
    }
    sb.append("</div>")
    return sb.toString()
}

/**
 * Temp-password notice: for a fresh account ([newAccount] = true) or an admin
 * reset. The password is shown once here; the app forces a change at first
 * login. A new account also carries the installation's connection details
 * ([webUrl], [mcpUrl]) - where to sign in on the web and how to attach an AI
 * connector - built from server.yaml so the guidance fits each deployment.
 */
fun renderTempPasswordEmail(
    orgName: String,
    username: String,
    tempPassword: String,
    newAccount: Boolean,
    webUrl: String? = null,
    mcpUrl: String? = null,
): String {
    val title = if (newAccount) "Ο λογαριασμός σας δημιουργήθηκε" else "Ο κωδικός σας μηδενίστηκε"
    val lead = if (newAccount) {
        "Δημιουργήθηκε λογαριασμός για εσάς με όνομα χρήστη <b>$username</b>."
    } else {
        "Ο κωδικός του λογαριασμού <b>$username</b> μηδενίστηκε από διαχειριστή."
    }
    // The connection guide is onboarding material - only on a brand-new account.
    val guide = if (newAccount) connectionGuide(webUrl, mcpUrl) else ""
    return AUTH_EMAIL_HEAD + card(
        emailMasthead(orgName) +
            "<h2 style=\"margin:0 0 12px;font-size:20px;\">$title</h2>" +
            "<p style=\"margin:0 0 16px;font-size:14px;line-height:1.5;\">$lead Συνδεθείτε με τον προσωρινό κωδικό:</p>" +
            codeBox(tempPassword, big = false) +
            "<p style=\"margin:0;font-size:13px;color:#555;\">Με την πρώτη σύνδεση θα σας ζητηθεί να ορίσετε δικό σας κωδικό.</p>" +
            guide
    ) + AUTH_EMAIL_FOOT
}
