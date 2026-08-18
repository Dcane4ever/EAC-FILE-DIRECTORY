package ph.edu.eac.filedirectory.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends the email-verification link for newly-registered accounts, via
 * Gmail SMTP (see application.properties spring.mail.* / .env.example).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String baseUrl;

    public MailService(JavaMailSender mailSender,
                        @Value("${eac.mail.from}") String fromAddress,
                        @Value("${eac.app.base-url}") String baseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.baseUrl = baseUrl;
    }

    public void sendVerificationEmail(String toEmail, String fullName, String token) {
        String link = baseUrl + "/verify?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setSubject("Verify your EAC File Directory account");
        message.setText("""
                Hi %s,

                Thanks for registering for the EAC File Directory. Confirm this is your
                email address by clicking the link below:

                %s

                This link expires in 24 hours. If you didn't request this, you can
                ignore this email.

                - EAC File Directory
                """.formatted(fullName == null || fullName.isBlank() ? toEmail : fullName, link));

        try {
            mailSender.send(message);
        } catch (MailException e) {
            // Don't fail registration if SMTP isn't configured yet (e.g. local dev
            // before real credentials exist) - log it clearly instead so the flow
            // is still exercisable end-to-end without a working mail server.
            log.error("Could not send verification email to {} - verification link: {}", toEmail, link, e);
        }
    }
}
