package com.stageaccord.sharedkernel.idempotency;

public sealed interface IdempotencyReservation {
    record Reserved() implements IdempotencyReservation {}
    record InProgress() implements IdempotencyReservation {}
    record Replayed(int statusCode, byte[] responseCiphertext) implements IdempotencyReservation {
        public Replayed {
            if (statusCode < 200 || statusCode > 599) throw new IllegalArgumentException("invalid statusCode");
            if (responseCiphertext == null || responseCiphertext.length == 0) {
                throw new IllegalArgumentException("responseCiphertext must not be empty");
            }
            responseCiphertext = responseCiphertext.clone();
        }

        @Override public byte[] responseCiphertext() { return responseCiphertext.clone(); }
    }
}
