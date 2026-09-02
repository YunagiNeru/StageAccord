package com.stageaccord.identityaccess.infrastructure;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.application.ClientLinkMessageSender;

@Component
public final class MailClientLinkMessageSender implements ClientLinkMessageSender {
    private final ObjectProvider<JavaMailSender> mail;
    private final String fromAddress;
    private final String baseUrl;

    public MailClientLinkMessageSender(ObjectProvider<JavaMailSender> mail,
            @Value("${stage-accord.mail.from-address}") String fromAddress,
            @Value("${stage-accord.mail.verification-base-url}") String baseUrl) {
        this.mail = mail;
        this.fromAddress = fromAddress;
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendClientLink(String email, String token) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new MailSendException("client link delivery is not configured");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("案件へのアクセスリンク");
        message.setText(baseUrl + "/auth/link?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        sender.send(message);
    }
}
