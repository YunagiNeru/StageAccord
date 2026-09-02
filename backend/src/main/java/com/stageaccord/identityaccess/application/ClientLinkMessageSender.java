package com.stageaccord.identityaccess.application;

public interface ClientLinkMessageSender {
    void sendClientLink(String email, String token);
}
