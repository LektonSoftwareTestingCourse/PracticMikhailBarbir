package com.processing.api;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;

public abstract class BaseApiTest {

    protected static final String GATEWAY_BASE_URL = System.getProperty(
            "gateway.url",
            System.getenv().getOrDefault("GATEWAY_URL", "http://localhost:8080")
    );

    protected static final String SWITCH_BASE_URL = System.getProperty(
            "switch.url",
            System.getenv().getOrDefault("SWITCH_URL", "http://localhost:8082")
    );

    protected static final String AUTH_BASE_URL = System.getProperty(
            "auth.url",
            System.getenv().getOrDefault("AUTH_URL", "http://localhost:8083")
    );

    protected static final String LOGGER_BASE_URL = System.getProperty(
            "logger.url",
            System.getenv().getOrDefault("LOGGER_URL", "http://localhost:8088")
    );

    protected static final String BIN_LOOKUP_BASE_URL = System.getProperty(
            "binlookup.url",
            System.getenv().getOrDefault("BIN_LOOKUP_URL", "http://localhost:8096")
    );

    protected static final String MERCHANT_BASE_URL = System.getProperty(
            "merchant.url",
            System.getenv().getOrDefault("MERCHANT_URL", "http://localhost:8084")
    );

    protected static RequestSpecification gatewaySpec;
    protected static RequestSpecification switchSpec;
    protected static RequestSpecification authSpec;
    protected static RequestSpecification loggerSpec;
    protected static RequestSpecification binLookupSpec;
    protected static RequestSpecification merchantSpec;

    @BeforeAll
    public static void setUpSpecs() {
        gatewaySpec = new RequestSpecBuilder()
                .setBaseUri(GATEWAY_BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();

        switchSpec = new RequestSpecBuilder()
                .setBaseUri(SWITCH_BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();

        authSpec = new RequestSpecBuilder()
                .setBaseUri(AUTH_BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();

        loggerSpec = new RequestSpecBuilder()
                .setBaseUri(LOGGER_BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();

        binLookupSpec = new RequestSpecBuilder()
                .setBaseUri(BIN_LOOKUP_BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();

        merchantSpec = new RequestSpecBuilder()
                .setBaseUri(MERCHANT_BASE_URL)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .build();
    }
}
