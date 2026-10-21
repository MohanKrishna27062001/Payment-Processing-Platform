package com.mohan.payments.payment.processor;

/**
 * The processor understood the request and refused it permanently (expired authorization, malformed request,
 * unknown reference). Unlike {@link ProcessorUnavailableException} this will not succeed on retry.
 */
public class ProcessorRejectedException extends RuntimeException {

    private final String code;

    public ProcessorRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
