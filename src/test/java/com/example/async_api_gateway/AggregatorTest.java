package com.example.async_api_gateway;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

/**
 * Integration tests for the AggregatorVerticle
 */
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AggregatorTest {

  private static final int TEST_TIMEOUT_SECONDS = 15;

  @BeforeEach
  void deploy(Vertx vertx, VertxTestContext testContext) {
    vertx.deployVerticle(new AggregatorVerticle(), testContext.succeeding(id -> testContext.completeNow()));
  }

  @Test
  @Order(1)
  @DisplayName("Health check endpoint returns 200 OK")
  void healthCheckReturns200(Vertx vertx, VertxTestContext testContext) throws Throwable {
    WebClient client = WebClient.create(vertx);

    client.get(8080, "localhost", "/health")
      .send(testContext.succeeding(resp -> testContext.verify(() -> {
        Assertions.assertEquals(200, resp.statusCode(), "Health check should return 200");

        JsonObject body = resp.bodyAsJsonObject();
        Assertions.assertNotNull(body, "Response body should not be null");
        Assertions.assertEquals("UP", body.getString("status"), "Status should be UP");
        Assertions.assertTrue(body.containsKey("timestamp"), "Should contain timestamp");

        testContext.completeNow();
      })));

    Assertions.assertTrue(testContext.awaitCompletion(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    if (testContext.failed()) {
      throw testContext.causeOfFailure();
    }
  }

  @Test
  @Order(2)
  @DisplayName("Status endpoint returns circuit breaker states")
  void statusEndpointReturnsCircuitStates(Vertx vertx, VertxTestContext testContext) throws Throwable {
    WebClient client = WebClient.create(vertx);

    client.get(8080, "localhost", "/status")
      .send(testContext.succeeding(resp -> testContext.verify(() -> {
        Assertions.assertEquals(200, resp.statusCode());

        JsonObject body = resp.bodyAsJsonObject();
        Assertions.assertTrue(body.containsKey("posts_circuit"), "Should contain posts_circuit");
        Assertions.assertTrue(body.containsKey("users_circuit"), "Should contain users_circuit");

        JsonObject postsCircuit = body.getJsonObject("posts_circuit");
        Assertions.assertNotNull(postsCircuit.getString("state"), "Should have state");

        testContext.completeNow();
      })));

    Assertions.assertTrue(testContext.awaitCompletion(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    if (testContext.failed()) {
      throw testContext.causeOfFailure();
    }
  }

  @Test
  @Order(3)
  @DisplayName("GET /aggregate returns aggregated data from both APIs")
  void aggregateReturnsData(Vertx vertx, VertxTestContext testContext) throws Throwable {
    WebClient client = WebClient.create(vertx);

    client.get(8080, "localhost", "/aggregate")
      .send(testContext.succeeding(resp -> testContext.verify(() -> {
        Assertions.assertEquals(200, resp.statusCode(), "Should return 200 OK");

        JsonObject body = resp.bodyAsJsonObject();
        Assertions.assertNotNull(body, "Response body should not be null");

        // Verify required fields
        Assertions.assertTrue(body.containsKey("post_title"), "Should contain post_title");
        Assertions.assertTrue(body.containsKey("author_name"), "Should contain author_name");
        Assertions.assertTrue(body.containsKey("response_time_ms"), "Should contain response_time_ms");

        // Verify actual values from jsonplaceholder
        String title = body.getString("post_title");
        String name = body.getString("author_name");

        Assertions.assertNotNull(title, "post_title should not be null");
        Assertions.assertNotNull(name, "author_name should not be null");
        Assertions.assertNotEquals("N/A", title, "post_title should have actual value");
        Assertions.assertNotEquals("Unknown", name, "author_name should have actual value");

        // Verify it contains expected content from jsonplaceholder
        Assertions.assertTrue(title.length() > 0, "post_title should not be empty");
        Assertions.assertTrue(name.length() > 0, "author_name should not be empty");

        // Verify response time is reasonable
        long responseTime = body.getLong("response_time_ms");
        Assertions.assertTrue(responseTime > 0, "Response time should be positive");
        Assertions.assertTrue(responseTime < 10000, "Response time should be under 10 seconds");

        testContext.completeNow();
      })));

    Assertions.assertTrue(testContext.awaitCompletion(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    if (testContext.failed()) {
      throw testContext.causeOfFailure();
    }
  }

  @Test
  @Order(4)
  @DisplayName("GET /aggregate handles errors gracefully")
  void aggregateHandlesErrorsGracefully(Vertx vertx, VertxTestContext testContext) throws Throwable {
    WebClient client = WebClient.create(vertx);

    // This test verifies the response structure is always valid JSON
    // even when external APIs might have issues
    client.get(8080, "localhost", "/aggregate")
      .send(testContext.succeeding(resp -> testContext.verify(() -> {
        int status = resp.statusCode();
        Assertions.assertTrue(status == 200 || status == 502,
          "Should return either 200 (success) or 502 (gateway error)");

        JsonObject body = resp.bodyAsJsonObject();
        Assertions.assertNotNull(body, "Response should always be valid JSON");

        if (status == 200) {
          // Success case
          Assertions.assertTrue(body.containsKey("post_title"));
          Assertions.assertTrue(body.containsKey("author_name"));
        } else {
          // Error case
          Assertions.assertTrue(body.containsKey("error"),
            "Error response should contain 'error' field");
          Assertions.assertTrue(body.containsKey("details"),
            "Error response should contain 'details' field");
        }

        // Both should have response time
        Assertions.assertTrue(body.containsKey("response_time_ms"),
          "Should contain response_time_ms");

        testContext.completeNow();
      })));

    Assertions.assertTrue(testContext.awaitCompletion(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    if (testContext.failed()) {
      throw testContext.causeOfFailure();
    }
  }

  @AfterEach
  void cleanup(Vertx vertx, VertxTestContext testContext) {
    vertx.close(testContext.succeeding(v -> testContext.completeNow()));
  }
}
