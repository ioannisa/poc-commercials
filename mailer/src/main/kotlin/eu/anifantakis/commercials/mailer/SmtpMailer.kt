package eu.anifantakis.commercials.mailer

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

/** SMTP settings, resolved by the host application (stations.yaml). */
data class SmtpSettings(
    val host: String,
    val port: Int = 587,
    val username: String? = null,
    val password: String? = null,
    /** The From address, e.g. "station@example.gr" (legacy: emailsetup.userid). */
    val from: String,
    val startTls: Boolean = true,
)

/**
 * Plain Jakarta Mail SMTP sender for the rendered HTML emails. One message
 * per call - the volumes here are the legacy app's (~120 emails/year), not
 * a campaign platform's.
 */
class SmtpMailer(private val settings: SmtpSettings) {

    fun sendHtml(to: String, subject: String, html: String) {
        val props = Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.starttls.enable", settings.startTls.toString())
            put("mail.smtp.auth", (settings.username != null).toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "20000")
        }
        val session = Session.getInstance(props)

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setContent(html, "text/html; charset=UTF-8")
        }

        if (settings.username != null) {
            Transport.send(message, settings.username, settings.password ?: "")
        } else {
            Transport.send(message)
        }
    }
}
