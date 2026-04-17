package com.sentinelpay.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around {@link JavaMailSender} for plain-text email delivery.
 *
 * <p>In development all outgoing mail is captured by Mailhog (port 8025).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${sentinelpay.mail.from:noreply@sentinelpay.com}")
    private String from;

    /**
     * Sends a plain-text email.  Throws {@link org.springframework.mail.MailException}
     * on delivery failure so the caller can record FAILED status.
     */
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.debug("Email sent to={} subject=\"{}\"", to, subject);
    }
}
