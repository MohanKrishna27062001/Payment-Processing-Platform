package com.mohan.payments.common.error;

import java.net.URI;
import java.util.Locale;

import com.mohan.payments.common.web.CorrelationIdFilter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds problem details with the fields every error response carries: type, code and correlationId. */
public final class Problems {

    private static final String TYPE_BASE = "https://payments.example.com/errors/";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return enrich(problem, code);
    }

    public static ProblemDetail enrich(ProblemDetail problem, String code) {
        problem.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        return problem;
    }
}
