package com.vncode.app.features.gs1;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A single opted-in reminder draft. Mailbox checks and reviewed sending remain explicit. */
public final class Gs1ReminderPolicy {
    public static final Duration MINIMUM_WAIT = Duration.ofDays(7);
    public enum Result { READY, NOT_OPTED_IN, UNSUPPORTED_REQUEST, DELIVERY_UNCONFIRMED, WAITING, REPLY_RECEIVED, ALREADY_PREPARED }
    private Gs1ReminderPolicy() {}

    public static Result evaluate(Gs1Repository.Request original, List<Gs1Repository.Message> messages,
                                  List<Gs1Repository.Request> requests, Instant now, boolean optedIn) {
        Objects.requireNonNull(original); Objects.requireNonNull(messages); Objects.requireNonNull(requests); Objects.requireNonNull(now);
        if (!optedIn) return Result.NOT_OPTED_IN;
        if (!original.kind().equals(Gs1LettersService.Kind.MORE_CODES.name())) return Result.UNSUPPORTED_REQUEST;
        if (original.state() != Gs1Repository.State.SENT) return Result.DELIVERY_UNCONFIRMED;
        if (requests.stream().anyMatch(r -> r.inn().equals(original.inn()) && r.kind().equals("REMINDER:" + original.id())))
            return Result.ALREADY_PREPARED;
        if (messages.stream().anyMatch(m -> !m.outgoing())) return Result.REPLY_RECEIVED;
        // createdAt is a draft time. Delay is measured from confirmed outgoing mail, never the draft.
        var sentAt = messages.stream().filter(Gs1Repository.Message::outgoing).map(Gs1Repository.Message::createdAt).max(Instant::compareTo);
        if (sentAt.isEmpty()) return Result.DELIVERY_UNCONFIRMED;
        return sentAt.get().plus(MINIMUM_WAIT).isAfter(now) ? Result.WAITING : Result.READY;
    }
}
