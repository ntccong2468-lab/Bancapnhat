package com.vncode.app.integration.gs1;

/** SMTP and IMAP use implicit TLS with server-identity checks; no insecure mode. */
public record Gs1MailAccount(String smtpHost,int smtpPort,String imapHost,int imapPort,
                             String username,String from,String protectedPassword) {
    public Gs1MailAccount {
        for(String host:new String[]{smtpHost,imapHost})if(host==null||!host.matches("[A-Za-z0-9.-]{1,253}")||host.startsWith(".")||host.endsWith("."))throw new IllegalArgumentException("Invalid mail server");
        if(smtpPort<1||smtpPort>65535||imapPort<1||imapPort>65535)throw new IllegalArgumentException("Invalid TLS port");
        if(username==null||username.isBlank()||username.matches("(?s).*[\\r\\n].*"))throw new IllegalArgumentException("Invalid mail username");
        if(from==null||!from.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}"))throw new IllegalArgumentException("Invalid sender address");
        if(protectedPassword==null||!protectedPassword.startsWith("dpapi:")||protectedPassword.length()<7)throw new IllegalArgumentException("Windows-protected mail password is required");
    }
    @Override public String toString(){return "Gs1MailAccount[TLS, password protected]";}
}
