package com.mohan.payments.payment.processor;

/** A transient processor failure (timeout, 5xx, rate limit): safe to retry later. */
public class ProcessorUnavailableException extends RuntimeException {

    public ProcessorUnavailableException(String message) {
        super(message);
    }
}
