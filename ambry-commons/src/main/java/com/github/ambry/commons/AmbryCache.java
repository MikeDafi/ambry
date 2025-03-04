/**
 * Copyright 2022 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.github.ambry.commons;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Implements a cache that maps a String to AmbryCacheEntry
 */
public class AmbryCache {

  private final Cache<String, AmbryCacheEntry> ambryCache;
  private final MetricRegistry metricRegistry;
  private static final Logger logger = LoggerFactory.getLogger(AmbryCache.class);
  private final String cacheId;
  private boolean cacheEnabled;
  private long cacheMaxSizeBytes;

  // Cache metrics
  public Meter getRequestRate;
  public Meter putRequestRate;
  public Meter deleteRequestRate;
  public Histogram getLatencyMs;
  public Histogram putLatencyMs;
  public Histogram deleteLatencyMs;
  public Counter getErrorCount;
  public Counter putErrorCount;
  public Counter deleteErrorCount;

  /**
   * Constructs an instance of AmbryCache
   * @param cacheId String identifier for this cache
   * @param cacheEnabled Toggles cache. If true, cache is enabled. Else, cache is disabled.
   * @param cacheMaxSizeBytes Maximum memory footprint of the cache
   * @param metricRegistry Instance of metrics registry to record stats
   */
  public AmbryCache(String cacheId, boolean cacheEnabled, long cacheMaxSizeBytes, MetricRegistry metricRegistry) {
    this.cacheId = cacheId;
    this.cacheEnabled = cacheEnabled;
    this.cacheMaxSizeBytes = cacheMaxSizeBytes;
    this.metricRegistry = metricRegistry;
    this.ambryCache = Caffeine.newBuilder()
        .maximumWeight(cacheMaxSizeBytes)
        .weigher((String key, AmbryCacheEntry value) -> key.length() + value.sizeBytes())
        .recordStats()
        .build();
    initializeAmbryCacheMetrics();
    logger.info("Initialized cache " + this);
  }

  /**
   * @return String representation of this cache
   */
  public String toString() {
    String result = "";
    result += "cacheId=" + cacheId;
    result += ", cacheEnabled=" + cacheEnabled;
    result += ", cacheMaxSizeBytes=" + cacheMaxSizeBytes;
    return result;
  }

  /**
   * Puts a key-value pair in the cache
   * @param key Object for indexing the cache
   * @param value Payload associated with key
   * @return True if successful. Else false.
   */
  public boolean putObject(String key, AmbryCacheEntry value) {

    if (!cacheEnabled) { return false; }

    putRequestRate.mark();
    long putStartTime = System.currentTimeMillis();
    boolean result = false;
    try {
      ambryCache.put(key, value);
      result = true;
    } catch (Exception exc) {
      putErrorCount.inc();
      logger.error("Failed to put cache entry with key " + key + " due to " + exc);
    } finally {
      putLatencyMs.update(System.currentTimeMillis() - putStartTime);
    }
    return result;
  }

  /**
   * Gets a value associated with a key
   * @param key Object for indexing the cache
   * @return Value associated with key, if present. Else, return null.
   */
  public AmbryCacheEntry getObject(String key) {

    if (!cacheEnabled) { return null; }

    getRequestRate.mark();
    long getStartTime = System.currentTimeMillis();
    AmbryCacheEntry result = null;
    try {
      result = ambryCache.getIfPresent(key);
    } catch (Exception exc) {
      getErrorCount.inc();
      logger.error("Failed to get cache entry with key " + key + " due to " + exc);
    } finally {
      getLatencyMs.update(System.currentTimeMillis() - getStartTime);
    }
    return result;
  }

  /**
   * Deletes a value associated with a key
   * @param key Object for indexing the cache
   * @return True, if delete succeeded. Else, false.
   */
  public boolean deleteObject(String key) {

    if (!cacheEnabled) { return false; }

    deleteRequestRate.mark();
    long deleteStartTime = System.currentTimeMillis();
    boolean result = false;
    try {
      ambryCache.invalidate(key);
      result = true;
    } catch (Exception exc) {
      deleteErrorCount.inc();
      logger.error("Failed to delete cache entry with key " + key + " due to " + exc);
    } finally {
      deleteLatencyMs.update(System.currentTimeMillis() - deleteStartTime);
    }
    return result;
  }

  /**
   * Initialize metrics for this instance of AmbryCache
   */
  private void initializeAmbryCacheMetrics() {

    if (!cacheEnabled) { return; }

    getErrorCount = metricRegistry.counter(MetricRegistry.name(AmbryCache.class, cacheId + "GetErrorCount"));
    putErrorCount = metricRegistry.counter(MetricRegistry.name(AmbryCache.class, cacheId + "PutErrorCount"));
    deleteErrorCount = metricRegistry.counter(MetricRegistry.name(AmbryCache.class, cacheId + "DeleteErrorCount"));
    getLatencyMs = metricRegistry.histogram(MetricRegistry.name(AmbryCache.class, cacheId + "GetLatencyMs"));
    putLatencyMs = metricRegistry.histogram(MetricRegistry.name(AmbryCache.class, cacheId + "PutLatencyMs"));
    deleteLatencyMs = metricRegistry.histogram(MetricRegistry.name(AmbryCache.class, cacheId + "DeleteLatencyMs"));
    getRequestRate = metricRegistry.meter(MetricRegistry.name(AmbryCache.class, cacheId + "GetRequestRate"));
    putRequestRate = metricRegistry.meter(MetricRegistry.name(AmbryCache.class, cacheId + "PutRequestRate"));
    deleteRequestRate = metricRegistry.meter(MetricRegistry.name(AmbryCache.class, cacheId + "DeleteRequestRate"));
    metricRegistry.register(MetricRegistry.name(AmbryCache.class, cacheId + "HitRate"), (Gauge<Double>) () -> ambryCache.stats().hitRate());
    metricRegistry.register(MetricRegistry.name(AmbryCache.class, cacheId + "MissRate"), (Gauge<Double>) () -> ambryCache.stats().missRate());
    metricRegistry.register(MetricRegistry.name(AmbryCache.class, cacheId + "NumEntries"), (Gauge<Long>) () -> ambryCache.estimatedSize());
  }
}
