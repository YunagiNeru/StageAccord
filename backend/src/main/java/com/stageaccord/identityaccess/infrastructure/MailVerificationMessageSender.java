package com.stageaccord.identityaccess.infrastructure;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.stageaccord.identityaccess.application.VerificationMessageSender;

@Component
public final class MailVerificationMessageSender implements VerificationMessageSender {
    private final ObjectProvider<JavaMailSender> mail;
    private final String fromAddress;
    private final String verificationBaseUrl;

    public MailVerificationMessageSender(ObjectProvider<JavaMailSender> mail,
            @Value("${stage-accord.mail.from-address}") String fromAddress,
            @Value("${stage-accord.mail.verification-base-url}") String verificationBaseUrl) {
        this.mail = mail;
        this.fromAddress = fromAddress;
        this.verificationBaseUrl = verificationBaseUrl;
    }

    @Override
    public void sendEmailVerification(String email, UUID challengeId, String token) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new MailSendException("verification delivery is not configured");
        String link = verificationBaseUrl + "/verify-email?id=" + challengeId
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("メールアドレスを確認してください");
        message.setText(link);
        sender.send(message);
    }
}
