package com.example.async_api_gateway;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {

  private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(new MainVerticle())
      .onSuccess(id -> log.info("MainVerticle deployed successfully with ID: {}", id))
      .onFailure(err -> {
        log.error("Failed to deploy MainVerticle", err);
        System.exit(1);
      });
  }

  @Override
  public void start(Promise<Void> startPromise) {
    log.info("Starting MainVerticle...");

    vertx.deployVerticle(new AggregatorVerticle())
      .onSuccess(id -> {
        log.info("AggregatorVerticle deployed successfully with ID: {}", id);
        startPromise.complete();
      })
      .onFailure(err -> {
        log.error("Failed to deploy AggregatorVerticle", err);
        startPromise.fail(err);
      });
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    log.info("Stopping MainVerticle...");
    stopPromise.complete();
  }
}
