package com.example.async_api_gateway;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class AggregatorVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(AggregatorVerticle.class);

  private WebClient webClient;
  private SimpleCircuitBreaker postsBreaker;
  private SimpleCircuitBreaker usersBreaker;

  // External API endpoints - Using HTTP instead of HTTPS for testing
  private static final String POSTS_URL = "http://jsonplaceholder.typicode.com/posts/1";
  private static final String USERS_URL = "http://jsonplaceholder.typicode.com/users/1";

  // Configuration - Increased timeouts
  private static final int SERVER_PORT = 8080;
  private static final long TIMEOUT_MS = 30000;  // 30 seconds
  private static final int MAX_POOL_SIZE = 20;

  @Override
  public void start(Promise<Void> startPromise) {
    log.info("Starting AggregatorVerticle...");

    // Initialize WebClient with increased timeouts and connection options
    WebClientOptions options = new WebClientOptions()
      .setMaxPoolSize(MAX_POOL_SIZE)
      .setKeepAlive(true)
      .setConnectTimeout(15000)      // 15 seconds to establish connection
      .setIdleTimeout(120)            // Keep connections alive for 2 minutes
      .setTryUseCompression(true)     // Enable compression
      .setFollowRedirects(true)       // Follow redirects
      .setMaxRedirects(5)             // Max 5 redirects
      .setTrustAll(true);             // Trust all certificates (for demo only)

    webClient = WebClient.create(vertx, options);

    // Initialize circuit breakers with higher threshold
    postsBreaker = new SimpleCircuitBreaker("posts", 5, Duration.ofSeconds(30));
    usersBreaker = new SimpleCircuitBreaker("users", 5, Duration.ofSeconds(30));

    // Setup router
    Router router = Router.router(vertx);

    // Health check endpoint
    router.get("/health").handler(this::handleHealthCheck);

    // Main aggregation endpoint
    router.get("/aggregate").handler(this::handleAggregate);

    // Circuit breaker status endpoint
    router.get("/status").handler(this::handleStatus);

    // Start HTTP server
    vertx.createHttpServer()
      .requestHandler(router)
      .listen(SERVER_PORT, res -> {
        if (res.succeeded()) {
          log.info("HTTP server started on port {}", SERVER_PORT);
          startPromise.complete();
        } else {
          log.error("Failed to start HTTP server", res.cause());
          startPromise.fail(res.cause());
        }
      });
  }

  private void handleHealthCheck(RoutingContext ctx) {
    JsonObject health = new JsonObject()
      .put("status", "UP")
      .put("timestamp", System.currentTimeMillis());

    ctx.response()
      .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
      .setStatusCode(200)
      .end(health.encodePrettily());
  }

  private void handleStatus(RoutingContext ctx) {
    JsonObject status = new JsonObject()
      .put("posts_circuit", new JsonObject()
        .put("state", postsBreaker.getState())
        .put("failures", postsBreaker.getFailureCount())
        .put("info", postsBreaker.toString()))
      .put("users_circuit", new JsonObject()
        .put("state", usersBreaker.getState())
        .put("failures", usersBreaker.getFailureCount())
        .put("info", usersBreaker.toString()));

    ctx.response()
      .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
      .setStatusCode(200)
      .end(status.encodePrettily());
  }

  private void handleAggregate(RoutingContext ctx) {
    long startTime = System.currentTimeMillis();
    log.info("Received /aggregate request from {}", ctx.request().remoteAddress());

    // Kick off both calls in parallel
    Future<JsonObject> postFuture = callExternalApi(POSTS_URL, postsBreaker);
    Future<JsonObject> userFuture = callExternalApi(USERS_URL, usersBreaker);

    CompositeFuture.all(postFuture, userFuture).onComplete(ar -> {
      long duration = System.currentTimeMillis() - startTime;

      if (ar.succeeded()) {
        JsonObject postObj = postFuture.result();
        JsonObject userObj = userFuture.result();

        String title = postObj.getString("title", "N/A");
        String name = userObj.getString("name", "Unknown");

        JsonObject aggregated = new JsonObject()
          .put("post_title", title)
          .put("author_name", name)
          .put("response_time_ms", duration);

        log.info("Successfully aggregated data in {}ms", duration);

        ctx.response()
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
          .setStatusCode(200)
          .end(aggregated.encodePrettily());
      } else {
        Throwable cause = ar.cause();
        log.error("Failed to aggregate upstream data (took {}ms)", duration, cause);

        JsonObject err = new JsonObject()
          .put("error", "Failed to retrieve upstream data")
          .put("details", cause == null ? "unknown" : cause.getMessage())
          .put("response_time_ms", duration);

        ctx.response()
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
          .setStatusCode(502)
          .end(err.encodePrettily());
      }
    });
  }

  private Future<JsonObject> callExternalApi(String url, SimpleCircuitBreaker breaker) {
    Promise<JsonObject> promise = Promise.promise();

    if (!breaker.allowRequest()) {
      log.warn("Circuit breaker {} is {}, rejecting request to {}",
        breaker.getName(), breaker.getState(), url);
      promise.fail("circuit_open: " + breaker.getName());
      return promise.future();
    }

    log.info("Calling external API: {}", url);

    // Parse URL to get host, port, and path
    String protocol = url.startsWith("https") ? "https" : "http";
    String hostAndPath = url.replace("https://", "").replace("http://", "");
    String[] parts = hostAndPath.split("/", 2);
    String host = parts[0];
    String path = "/" + (parts.length > 1 ? parts[1] : "");
    int port = protocol.equals("https") ? 443 : 80;

    log.info("Connecting to: {}:{}{}", host, port, path);

    webClient
      .get(port, host, path)
      .ssl(protocol.equals("https"))
      .timeout(TIMEOUT_MS)
      .send(ar -> {
        if (ar.succeeded()) {
          int status = ar.result().statusCode();
          log.info("Received response from {} with status {}", url, status);

          if (status >= 200 && status < 300) {
            try {
              JsonObject body = ar.result().bodyAsJsonObject();
              breaker.recordSuccess();
              log.info("Successfully parsed JSON from {}", url);
              promise.complete(body);
            } catch (Exception e) {
              breaker.recordFailure();
              log.error("Invalid JSON from {}", url, e);
              promise.fail("invalid_json: " + e.getMessage());
            }
          } else {
            breaker.recordFailure();
            log.warn("Upstream {} returned non-2xx status: {}", url, status);
            promise.fail("upstream_status_" + status);
          }
        } else {
          breaker.recordFailure();
          log.error("Failed to call upstream {}: {}", url, ar.cause().getMessage());
          promise.fail(ar.cause());
        }
      });

    return promise.future();
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    log.info("Stopping AggregatorVerticle...");
    if (webClient != null) {
      webClient.close();
    }
    stopPromise.complete();
  }
}
