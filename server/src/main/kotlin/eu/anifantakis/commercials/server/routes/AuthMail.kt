package eu.anifantakis.commercials.server.routes

import eu.anifantakis.commercials.mailer.SmtpMailer
import eu.anifantakis.commercials.scheduleemail.ScheduleEmailAssembler.toSettings
import eu.anifantakis.commercials.server.stations.StationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fire-and-forget auth email (reset code / temp password), sent on the
 * application scope so the request responds immediately. That both avoids
 * blocking on a slow SMTP and flattens the timing side-channel a
 * send-only-when-the-account-exists path would leak. A missing file-wide SMTP
 * or a send failure is swallowed: no flow depends on it - the admin path always
 * has the temp password on screen, the reset path always answers identically.
 *
 * Honours the server.yaml `emailRedirectTo` TEST override exactly like the
 * schedule emails: the mail goes to the test account, tagged with the intended
 * recipient in the subject.
 */
internal fun CoroutineScope.sendAuthMail(
    registry: StationRegistry,
    intendedTo: String,
    subject: String,
    html: String,
) {
    val smtp = registry.defaultSmtp ?: return
    launch(Dispatchers.IO) {
        runCatching {
            val redirect = registry.emailRedirectTo
            val to = redirect ?: intendedTo
            val finalSubject = if (redirect != null) "[TEST → $intendedTo] $subject" else subject
            SmtpMailer(smtp.toSettings()).sendHtml(to, finalSubject, html)
        }
    }
}
