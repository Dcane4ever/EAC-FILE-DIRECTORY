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

        send(message, "verification", toEmail, link);
    }

    /** Same token/link shape as sendVerificationEmail, just a fresh one issued via the resend-verification flow - see RegistrationController. */
    public void sendVerificationEmail(String toEmail, String fullName, String token, boolean isResend) {
        if (!isResend) {
            sendVerificationEmail(toEmail, fullName, token);
            return;
        }
        String link = baseUrl + "/verify?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setSubject("Verify your EAC File Directory account");
        message.setText("""
                Hi %s,

                Here's a fresh verification link for your EAC File Directory account:

                %s

                This link expires in 24 hours. If you didn't request this, you can
                ignore this email.

                - EAC File Directory
                """.formatted(fullName == null || fullName.isBlank() ? toEmail : fullName, link));

        send(message, "resend-verification", toEmail, link);
    }

    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        String link = baseUrl + "/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setSubject("Reset your EAC File Directory password");
        message.setText("""
                Hi %s,

                Someone (hopefully you) asked to reset the password for this EAC File
                Directory account. Click the link below to set a new password:

                %s

                This link expires in 1 hour and can only be used once. If you didn't
                request this, you can safely ignore this email - your password will
                not be changed.

                - EAC File Directory
                """.formatted(fullName == null || fullName.isBlank() ? toEmail : fullName, link));

        send(message, "password-reset", toEmail, link);
    }

    public void sendAccessRequestedEmail(String uploaderEmail, String uploaderName, String requesterName, String fileTitle) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(uploaderEmail);
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setSubject("Someone requested access to \"" + fileTitle + "\"");
        message.setText("""
                Hi %s,

                %s has requested access to download your file "%s" on the EAC File
                Directory. Sign in and open My Uploads > Access Requests to approve or
                deny it:

                %s/my-uploads/access-requests

                - EAC File Directory
                """.formatted(uploaderName == null || uploaderName.isBlank() ? uploaderEmail : uploaderName,
                requesterName, fileTitle, baseUrl));

        send(message, "access-requested", uploaderEmail, baseUrl + "/my-uploads/access-requests");
    }

    public void sendAccessApprovedEmail(String requesterEmail, String requesterName, String fileTitle) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(requesterEmail);
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setSubject("Access approved: \"" + fileTitle + "\"");
        message.setText("""
                Hi %s,

                Your request to download "%s" was approved. Sign in and open My
                Requests to get your download link - it expires 6 hours after approval
                and works only while signed in as you:

                %s/my-requests

                - EAC File Directory
                """.formatted(requesterName == null || requesterName.isBlank() ? requesterEmail : requesterName,
                fileTitle, baseUrl));

        send(message, "access-approved", requesterEmail, baseUrl + "/my-requests");
    }

    public void sendAccessDeniedEmail(String requesterEmail, String requesterName, String fileTitle, String reason) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(requesterEmail);
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setSubject("Access denied: \"" + fileTitle + "\"");
        String reasonLine = reason == null || reason.isBlank() ? "" : "\nReason: " + reason + "\n";
        message.setText("""
                Hi %s,

                Your request to download "%s" was denied.
                %s
                - EAC File Directory
                """.formatted(requesterName == null || requesterName.isBlank() ? requesterEmail : requesterName,
                fileTitle, reasonLine));

        send(message, "access-denied", requesterEmail, baseUrl + "/my-requests");
    }

    private void send(SimpleMailMessage message, String kind, String toEmail, String link) {
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // Don't fail the calling flow if SMTP isn't configured yet (e.g. local
            // dev before real credentials exist) - log it clearly instead so the
            // flow is still exercisable end-to-end without a working mail server.
            log.error("Could not send {} email to {} - link: {}", kind, toEmail, link, e);
        }
    }
}
