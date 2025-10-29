package com.example.async_api_gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple circuit breaker implementation with three states:
 * - CLOSED: Normal operation, requests allowed
 * - OPEN: After failureThreshold consecutive failures, rejects all requests
 * - HALF_OPEN: After resetDuration, allows one test request to check if service recovered
 *
 * This is a basic implementation for demonstration purposes.
 * For production, consider using Vert.x Circuit Breaker or Resilience4j.
 */
public class SimpleCircuitBreaker {

  private static final Logger log = LoggerFactory.getLogger(SimpleCircuitBreaker.class);

  private final String name;
  private final int failureThreshold;
  private final Duration resetDuration;

  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private volatile Instant openUntil = Instant.EPOCH;
  private volatile boolean halfOpen = false;

  /**
   * Creates a new circuit breaker
   *
   * @param name Identifier for this circuit breaker
   * @param failureThreshold Number of consecutive failures before opening
   * @param resetDuration How long to wait before attempting recovery
   */
  public SimpleCircuitBreaker(String name, int failureThreshold, Duration resetDuration) {
    this.name = name;
    this.failureThreshold = failureThreshold;
    this.resetDuration = resetDuration;
    log.info("Circuit breaker '{}' initialized (threshold={}, reset={})",
      name, failureThreshold, resetDuration);
  }

  /**
   * Check if a request should be allowed through
   *
   * @return true if request is allowed, false if circuit is open
   */
  public boolean allowRequest() {
    if (isOpen()) {
      // Check if reset duration has passed
      if (Instant.now().isAfter(openUntil)) {
        // Enter half-open state: allow one test request
        log.info("Circuit '{}' entering HALF_OPEN state", name);
        halfOpen = true;
        return true;
      }
      return false;
    }
    return true;
  }

  /**
   * Check if circuit is currently open
   */
  private boolean isOpen() {
    return Instant.now().isBefore(openUntil);
  }

  /**
   * Record a successful request
   * Resets failure counter and closes circuit if in half-open state
   */
  public void recordSuccess() {
    int previousFailures = consecutiveFailures.getAndSet(0);

    // If we were in half-open, close the circuit
    if (halfOpen) {
      halfOpen = false;
      openUntil = Instant.EPOCH;
      log.info("Circuit '{}' closed after successful test request", name);
    } else if (previousFailures > 0) {
      log.debug("Circuit '{}' recovered, failures reset from {}", name, previousFailures);
    }
  }

  /**
   * Record a failed request
   * Increments failure counter and opens circuit if threshold reached
   */
  public void recordFailure() {
    // If in half-open and failed, re-open immediately
    if (halfOpen) {
      halfOpen = false;
      openUntil = Instant.now().plus(resetDuration);
      log.warn("Circuit '{}' re-opened after failed test request (open until {})",
        name, openUntil);
      return;
    }

    int fails = consecutiveFailures.incrementAndGet();
    log.debug("Circuit '{}' recorded failure #{}", name, fails);

    if (fails >= failureThreshold) {
      // Trip circuit
      openUntil = Instant.now().plus(resetDuration);
      log.error("Circuit '{}' OPENED after {} failures (open until {})",
        name, fails, openUntil);
    }
  }

  /**
   * Get the name of this circuit breaker
   */
  public String getName() {
    return name;
  }

  /**
   * Get current state as string
   */
  public String getState() {
    if (halfOpen) return "HALF_OPEN";
    if (isOpen()) return "OPEN";
    return "CLOSED";
  }

  /**
   * Get current failure count
   */
  public int getFailureCount() {
    return consecutiveFailures.get();
  }

  @Override
  public String toString() {
    return "SimpleCircuitBreaker[" +
      "name=" + name +
      ", state=" + getState() +
      ", failures=" + consecutiveFailures.get() +
      ", openUntil=" + openUntil +
      "]";
  }
}
