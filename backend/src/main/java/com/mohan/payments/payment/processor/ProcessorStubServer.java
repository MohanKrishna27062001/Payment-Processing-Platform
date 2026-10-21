package com.mohan.payments.payment.processor;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the fake payment processor inside this JVM, so a local run needs nothing but the application itself:
 * no Docker, no second service to start, one command.
 * <p>
 * It is a real HTTP server on its own port, not an in-process shortcut, so the calls your code makes are
 * genuinely calls over the network - which is the point. Timeouts, connection resets and slow responses all
 * behave as they would against a real processor.
 * <p>
 * Behaviour comes from the JSON files under {@code stubs/mappings/}; edit one and
 * {@code POST http://localhost:8089/__admin/mappings/reset} reloads it without restarting the application.
 * <p>
 * Off unless {@code app.processor.stub-server.enabled=true}, which the {@code local} profile sets.
 */
@Configuration
@ConditionalOnProperty(name = "app.processor.stub-server.enabled", havingValue = "true")
public class ProcessorStubServer {

    private static final Logger log = LoggerFactory.getLogger(ProcessorStubServer.class);

    /** Named differently from the class: Spring registers this @Configuration class as 'processorStubServer'. */
    @Bean(destroyMethod = "stop")
    public WireMockServer fakePaymentProcessorServer(
            @Value("${app.processor.stub-server.port:8089}") int port,
            @Value("${app.processor.stub-server.root:stubs}") String root) {

        Path directory = Path.of(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory.resolve("mappings"))) {
            throw new IllegalStateException("No processor stub mappings at " + directory.resolve("mappings")
                    + ". Start the application from the 'backend' directory, or point "
                    + "app.processor.stub-server.root at the folder that contains 'mappings'.");
        }

        WireMockServer server = new WireMockServer(options()
                .port(port)
                .usingFilesUnderDirectory(directory.toString())
                .globalTemplating(true));
        server.start();

        log.info("Fake payment processor listening on http://localhost:{} (mappings from {})", port, directory);
        log.info("  what the app sent:  http://localhost:{}/__admin/requests", port);
        log.info("  reload after edits: POST http://localhost:{}/__admin/mappings/reset", port);
        log.info("  rewind scenarios:   POST http://localhost:{}/__admin/scenarios/reset", port);
        return server;
    }
}
