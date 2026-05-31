package com.fxbrief.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.payment.config.MidtransProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin client for the Midtrans Snap API. Creates a Snap transaction and returns the
 * {@code snap_token} the frontend uses to open the Snap popup.
 *
 * <p>The base URL is resolved from {@link MidtransProperties#snapBaseUrl()}, which is
 * driven entirely by the {@code production} flag: flipping the environment variable
 * switches between the sandbox and production Snap endpoints with no code change. Auth is
 * HTTP Basic with the server key as the username and an empty password, base64-encoded
 * per the Midtrans specification.
 *
 * <p>All transient HTTP/network failures are translated to a {@link DomainException}
 * before reaching the Resilience4j aspect, matching the other outbound clients
 * (D-041). The retry/circuit-breaker instance is {@code midtrans}.
 */
@Slf4j
@Component
public class MidtransClient {

    public static final String RESILIENCE_NAME = "midtrans";

    private static final String CREATE_TRANSACTION_PATH = "/snap/v1/transactions";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public MidtransClient(MidtransProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.snapBaseUrl())
                .requestFactory(buildRequestFactory(properties))
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuthHeader(properties.serverKey()))
                .build();
    }

    private static ClientHttpRequestFactory buildRequestFactory(MidtransProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return factory;
    }

    private static String basicAuthHeader(String serverKey) {
        String credentials = serverKey + ":";
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a Snap transaction for the given order. {@code grossAmountIdr} must be a
     * whole-rupiah integer — Midtrans rejects fractional gross amounts for IDR.
     */
    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    public SnapToken createSnapTransaction(String orderId, long grossAmountIdr,
                                           String itemName, String customerEmail) {
        ObjectNode body = objectMapper.createObjectNode();

        ObjectNode transactionDetails = body.putObject("transaction_details");
        transactionDetails.put("order_id", orderId);
        transactionDetails.put("gross_amount", grossAmountIdr);

        ObjectNode item = body.putArray("item_details").addObject();
        item.put("id", orderId);
        item.put("price", grossAmountIdr);
        item.put("quantity", 1);
        item.put("name", itemName);

        if (customerEmail != null && !customerEmail.isBlank()) {
            body.putObject("customer_details").put("email", customerEmail);
        }

        try {
            String responseBody = restClient.post()
                    .uri(CREATE_TRANSACTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return parseToken(responseBody);
        } catch (HttpClientErrorException e) {
            log.warn("Midtrans Snap client error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw createFailure("Midtrans client error " + e.getStatusCode());
        } catch (HttpServerErrorException | ResourceAccessException e) {
            log.warn("Midtrans Snap transient failure: {}", e.getMessage());
            throw createFailure("Midtrans transient failure: " + e.getMessage());
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Midtrans Snap unexpected failure: {}", e.getMessage());
            throw createFailure("Midtrans unexpected failure: " + e.getMessage());
        }
    }

    private SnapToken parseToken(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw createFailure("Midtrans empty response");
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String token = root.path("token").asText(null);
            if (token == null || token.isBlank()) {
                throw createFailure("Midtrans response missing token");
            }
            String redirectUrl = root.path("redirect_url").asText(null);
            return new SnapToken(token, redirectUrl);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw createFailure("Midtrans response parse error: " + e.getMessage());
        }
    }

    private DomainException createFailure(String message) {
        return new DomainException(
                ErrorCodes.PAYMENT_INITIATION_FAILED,
                HttpStatus.SERVICE_UNAVAILABLE,
                message);
    }

    public record SnapToken(String token, String redirectUrl) {}
}
