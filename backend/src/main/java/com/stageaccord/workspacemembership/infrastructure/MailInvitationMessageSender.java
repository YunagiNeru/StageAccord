package com.stageaccord.workspacemembership.infrastructure;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.stageaccord.workspacemembership.application.InvitationMessageSender;

@Component
public final class MailInvitationMessageSender implements InvitationMessageSender {
    private final ObjectProvider<JavaMailSender> mail;
    private final String fromAddress;
    private final String baseUrl;

    public MailInvitationMessageSender(ObjectProvider<JavaMailSender> mail,
            @Value("${stage-accord.mail.from-address}") String fromAddress,
            @Value("${stage-accord.mail.verification-base-url}") String baseUrl) {
        this.mail = mail;
        this.fromAddress = fromAddress;
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendInvitation(String email, UUID workspaceId, UUID invitationId, String token) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new MailSendException("invitation delivery is not configured");
        String link = baseUrl + "/invitations/" + workspaceId + "/" + invitationId
                + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("ワークスペースへの招待");
        message.setText(link);
        sender.send(message);
    }
}
