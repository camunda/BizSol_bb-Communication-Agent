package io.camunda.bizsol.bb.comm_agent.testutil;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.Properties;

public final class EmailTestUtil {

    private EmailTestUtil() {
    }

    /**
     * Sends a multipart test email with a plain text plainTextBody and optional attachments loaded
     * from the classpath.
     *
     * @param smtpHost SMTP host used by the test mail server.
     * @param smtpPort SMTP port used by the test mail server.
     * @param from sender email address.
     * @param to recipient email address(es), comma-separated.
     * @param subject subject line for the message.
     * @param plainTextBody plain text plainTextBody for the message.
     * @param attachmentClasspathResources zero or more classpath resource paths to attach. Blank or
     *     null entries are ignored.
     * @throws IllegalStateException when the message cannot be created or sent, or when an
     *     attachment resource cannot be found on the classpath.
     */
    public static void sendSampleEmail(
            String smtpHost,
            int smtpPort,
            String from,
            String to,
            String subject,
            String plainTextBody,
            String... attachmentClasspathResources) {
        Properties properties = new Properties();
        properties.put("mail.smtp.host", smtpHost);
        properties.put("mail.smtp.port", String.valueOf(smtpPort));
        properties.put("mail.smtp.auth", "false");
        properties.put("mail.smtp.starttls.enable", "false");

        Session session = Session.getInstance(properties);
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(plainTextBody, "UTF-8", "plain");

            MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);

            if (attachmentClasspathResources != null) {
                for (String resourcePath : attachmentClasspathResources) {
                    if (resourcePath == null || resourcePath.isBlank()) {
                        continue;
                    }
                    MimeBodyPart attachmentPart = createAttachmentPart(resourcePath);
                    multipart.addBodyPart(attachmentPart);
                }
            }

            message.setContent(multipart);
            Transport.send(message);
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException("Failed to send test email", e);
        }
    }

    private static MimeBodyPart createAttachmentPart(String resourcePath)
            throws MessagingException, IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = EmailTestUtil.class.getClassLoader();
        }
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "Attachment resource not found on classpath: " + resourcePath);
            }
            String contentType = URLConnection.guessContentTypeFromName(resourcePath);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            ByteArrayDataSource dataSource = new ByteArrayDataSource(inputStream, contentType);
            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.setDataHandler(new jakarta.activation.DataHandler(dataSource));
            attachmentPart.setFileName(extractFilename(resourcePath));
            return attachmentPart;
        }
    }

    private static String extractFilename(String resourcePath) {
        int lastSlash = resourcePath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < resourcePath.length() - 1) {
            return resourcePath.substring(lastSlash + 1);
        }
        return resourcePath;
    }
}
