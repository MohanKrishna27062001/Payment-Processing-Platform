package com.mohan.payments.outbox;

/** A failure that retrying cannot fix (bad data, missing payment): the job is dead-lettered immediately. */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }
}
