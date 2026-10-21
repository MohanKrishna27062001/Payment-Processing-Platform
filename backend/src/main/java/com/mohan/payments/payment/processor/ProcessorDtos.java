package com.mohan.payments.payment.processor;

/**
 * Wire format for the processor API. Unknown fields are ignored: Spring Boot's object mapper does not fail on
 * them, so the stub can return extra diagnostic fields without breaking the client.
 */
final class ProcessorDtos {

    private ProcessorDtos() {
    }

    record AuthorizeRequest(String paymentMethodToken, long amountMinor, String currency) {

        /** Records print every field by default; never let a card token reach a log line. */
        @Override
        public String toString() {
            return "AuthorizeRequest[amountMinor=" + amountMinor + ", currency=" + currency
                    + ", paymentMethodToken=****]";
        }
    }

    record AmountRequest(long amountMinor) {
    }

    record Card(String brand, String last4) {
    }

    record AuthorizeResponse(boolean approved, String processorReference, Card card, String declineReason,
            String error) {
    }

    record OperationResponse(String error) {
    }
}
