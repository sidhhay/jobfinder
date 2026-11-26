package com.jobfinder.service;

import com.jobfinder.config.AppSettings;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.Properties;

/** Sends account verification emails over SMTP. Does nothing when SMTP is not configured. */
@Service
public class EmailService {

    private final AppSettings settings;

    public EmailService(AppSettings settings) {
        this.settings = settings;
    }

    public void sendVerificationEmail(String recipientEmail, String recipientName, String code) {
        if (settings.smtpHost() == null) {
            return;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.smtpHost());
        sender.setPort(settings.smtpPort());
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", "15000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");
        if (settings.smtpUseTls()) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        if (settings.smtpUsername() != null) {
            sender.setUsername(settings.smtpUsername());
            sender.setPassword(settings.smtpPassword());
            properties.put("mail.smtp.auth", "true");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        String from = settings.smtpFromEmail() != null ? settings.smtpFromEmail() : settings.smtpUsername();
        message.setFrom(from != null ? from : "no-reply@jobful.local");
        message.setTo(recipientEmail);
        message.setSubject("Verify your Jobful email");
        message.setText("Hi " + recipientName + ",\n\n"
                + "Your Jobful verification code is: " + code + "\n\n"
                + "This code expires in " + settings.emailCodeMinutes() + " minutes.\n\n"
                + "If you did not request this, you can ignore this email.");
        sender.send(message);
    }
}
