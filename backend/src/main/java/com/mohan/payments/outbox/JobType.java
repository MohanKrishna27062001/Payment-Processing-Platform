package com.mohan.payments.outbox;

/** Kinds of async work. Adding a workflow (e.g. merchant webhooks) means a new type and an {@link OutboxJobHandler}. */
public enum JobType {
    SETTLE_PAYMENT
}
