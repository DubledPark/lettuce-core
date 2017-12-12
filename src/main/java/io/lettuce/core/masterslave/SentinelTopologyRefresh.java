/*
 * Copyright 2011-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.masterslave;

import java.io.Closeable;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.internal.AsyncCloseable;
import io.lettuce.core.internal.LettuceLists;
import io.lettuce.core.protocol.LettuceCharsets;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Sentinel Pub/Sub listener-enabled topology refresh. This refresh triggers topology updates if Redis topology changes
 * (monitored master/slaves) or the Sentinel availability changes.
 *
 * @author Mark Paluch
 * @since 4.2
 */
class SentinelTopologyRefresh implements AsyncCloseable, Closeable {

    private static final InternalLogger LOG = InternalLoggerFactory.getInstance(SentinelTopologyRefresh.class);
    private static final StringCodec CODEC = new StringCodec(LettuceCharsets.ASCII);
    private static final Set<String> PROCESSING_CHANNELS = new HashSet<>(
            Arrays.asList("failover-end", "failover-end-for-timeout"));

    private final Map<RedisURI, ConnectionFuture<StatefulRedisPubSubConnection<String, String>>> pubSubConnections = new ConcurrentHashMap<>();
    private final RedisClient redisClient;
    private final List<RedisURI> sentinels;
    private final List<Runnable> refreshRunnables = new CopyOnWriteArrayList<>();
    private final RedisPubSubAdapter<String, String> adapter = new RedisPubSubAdapter<String, String>() {

        @Override
        public void message(String pattern, String channel, String message) {
            SentinelTopologyRefresh.this.processMessage(pattern, channel, message);
        }
    };

    private final PubSubMessageActionScheduler topologyRefresh;
    private final PubSubMessageActionScheduler sentinelReconnect;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    private volatile boolean closed = false;

    SentinelTopologyRefresh(RedisClient redisClient, String masterId, List<RedisURI> sentinels) {

        this.redisClient = redisClient;
        this.sentinels = LettuceLists.newList(sentinels);
        this.topologyRefresh = new PubSubMessageActionScheduler(redisClient.getResources().eventExecutorGroup(),
                new TopologyRefreshMessagePredicate(masterId));
        this.sentinelReconnect = new PubSubMessageActionScheduler(redisClient.getResources().eventExecutorGroup(),
                new SentinelReconnectMessagePredicate());
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {

        if (closed) {
            return closeFuture;
        }

        closed = true;

        HashMap<RedisURI, ConnectionFuture<StatefulRedisPubSubConnection<String, String>>> connections = new HashMap<>(
                pubSubConnections);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        connections.forEach((k, f) -> {

            futures.add(f.exceptionally(t -> null).thenCompose(c -> {

                if (c == null) {
                    return CompletableFuture.completedFuture(null);
                }
                c.removeListener(adapter);
                return c.closeAsync();
            }).toCompletableFuture());

            pubSubConnections.remove(k);
        });

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((aVoid, throwable) -> {

            if (throwable != null) {
                closeFuture.completeExceptionally(throwable);
            } else {
                closeFuture.complete(null);
            }
        });

        return closeFuture;
    }

    CompletionStage<Void> bind(Runnable runnable) {

        refreshRunnables.add(runnable);

        return initializeSentinels();
    }

    private CompletionStage<Void> initializeSentinels() {

        if (closed) {
            return closeFuture;
        }

        List<ConnectionFuture<StatefulRedisPubSubConnection<String, String>>> connectionFutures = new ArrayList<>();

        Duration timeout = RedisURI.DEFAULT_TIMEOUT_DURATION;
        for (RedisURI sentinel : sentinels) {

            if (!pubSubConnections.containsKey(sentinel)) {

                ConnectionFuture<StatefulRedisPubSubConnection<String, String>> future = redisClient.connectPubSubAsync(CODEC,
                        sentinel);
                timeout = sentinel.getTimeout();
                pubSubConnections.put(sentinel, future);

                future.whenComplete((connection, throwable) -> {

                    if (throwable != null || closed) {
                        pubSubConnections.remove(sentinel);
                    }

                    if (closed) {
                        connection.closeAsync();
                    }
                });

                connectionFutures.add(future);
            }
        }

        if (connectionFutures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        SentinelTopologyRefreshConnections collector = new SentinelTopologyRefreshConnections(connectionFutures.size());

        if (closed) {
            return closeAsync();
        }

        for (ConnectionFuture<StatefulRedisPubSubConnection<String, String>> connectionFuture : connectionFutures) {

            connectionFuture.thenCompose(connection -> {
                connection.addListener(adapter);
                return connection.async().psubscribe("*").thenApply(v -> connection).whenComplete((c, t) -> {

                    if (t != null) {
                        connection.closeAsync();
                    }
                });
            }).whenComplete((connection, throwable) -> {

                if (throwable != null) {
                    collector.accept(throwable);
                } else {
                    collector.accept(connection);
                }
            });
        }

        CompletionStage<SentinelTopologyRefreshConnections> completionStage = collector.getOrTimeout(timeout, redisClient
                .getResources().eventExecutorGroup());

        return completionStage.whenComplete((aVoid, throwable) -> {

            if (throwable != null) {
                closeAsync();
            }
        }).thenApply(noop -> (Void) null);
    }

    private void processMessage(String pattern, String channel, String message) {

        topologyRefresh.processMessage(channel, message, () -> {
            LOG.debug("Received topology changed signal from Redis Sentinel ({}), scheduling topology update", channel);
            return () -> refreshRunnables.forEach(Runnable::run);
        });

        sentinelReconnect.processMessage(channel, message, () -> {

            LOG.debug("Received sentinel state changed signal from Redis Sentinel, scheduling sentinel reconnect attempts");

            return this::initializeSentinels;
        });
    }

    private static class PubSubMessageActionScheduler {

        private final TimedSemaphore timedSemaphore = new TimedSemaphore();
        private final EventExecutorGroup eventExecutors;
        private final MessagePredicate filter;

        PubSubMessageActionScheduler(EventExecutorGroup eventExecutors, MessagePredicate filter) {
            this.eventExecutors = eventExecutors;
            this.filter = filter;
        }

        void processMessage(String channel, String message, Supplier<Runnable> runnableSupplier) {

            if (!processingAllowed(channel, message)) {
                return;
            }

            timedSemaphore.onEvent(timeout -> {

                Runnable runnable = runnableSupplier.get();

                if (timeout == null) {
                    eventExecutors.submit(runnable);
                } else {
                    eventExecutors.schedule(runnable, timeout.remaining(), TimeUnit.MILLISECONDS);
                }

            });
        }

        private boolean processingAllowed(String channel, String message) {

            if (eventExecutors.isShuttingDown()) {
                return false;
            }

            if (!filter.test(channel, message)) {
                return false;
            }

            return true;
        }
    }

    /**
     * Lock-free semaphore that limits calls by using a {@link Timeout}. This class is thread-safe and
     * {@link #onEvent(Consumer)} may be called by multiple threads concurrently. It's guaranteed the first caller for an
     * expired {@link Timeout} will be called.
     */
    private static class TimedSemaphore {

        private final AtomicReference<Timeout> timeoutRef = new AtomicReference<>();

        private final int timeout = 5;
        private final TimeUnit timeUnit = TimeUnit.SECONDS;

        /**
         * Rate-limited method that notifies the given {@link Consumer} once the current {@link Timeout} is expired.
         *
         * @param timeoutConsumer callback.
         */
        protected void onEvent(Consumer<Timeout> timeoutConsumer) {

            Timeout existingTimeout = timeoutRef.get();

            if (existingTimeout != null) {
                if (!existingTimeout.isExpired()) {
                    return;
                }
            }

            Timeout timeout = new Timeout(this.timeout, this.timeUnit);
            boolean state = timeoutRef.compareAndSet(existingTimeout, timeout);

            if (state) {
                timeoutConsumer.accept(timeout);
            }
        }
    }

    static interface MessagePredicate extends BiPredicate<String, String> {

        @Override
        boolean test(String message, String channel);
    }

    /**
     * {@link MessagePredicate} to check whether the channel and message contain topology changes related to the monitored
     * master.
     */
    private static class TopologyRefreshMessagePredicate implements MessagePredicate {

        private final String masterId;

        TopologyRefreshMessagePredicate(String masterId) {
            this.masterId = masterId;
        }

        @Override
        public boolean test(String channel, String message) {

            // trailing spaces after the master name are not bugs
            if (channel.equals("+elected-leader")) {
                if (message.startsWith(String.format("master %s ", masterId))) {
                    return true;
                }
            }

            if (channel.equals("+switch-master")) {
                if (message.startsWith(String.format("%s ", masterId))) {
                    return true;
                }
            }

            if (channel.equals("fix-slave-config")) {
                if (message.contains(String.format("@ %s ", masterId))) {
                    return true;
                }
            }

            if (channel.equals("+convert-to-slave") || channel.equals("+role-change")) {
                if (message.contains(String.format("@ %s ", masterId))) {
                    return true;
                }
            }

            return PROCESSING_CHANNELS.contains(channel);
        }
    }

    /**
     * {@link MessagePredicate} to check whether the channel and message contain Sentinel availability changes or a Sentinel was
     * added.
     */
    private static class SentinelReconnectMessagePredicate implements MessagePredicate {

        @Override
        public boolean test(String message, String channel) {
            if (channel.equals("+sentinel")) {
                return true;
            }

            if (channel.equals("-odown") || channel.equals("-sdown")) {
                if (message.startsWith("sentinel ")) {
                    return true;
                }
            }

            return false;
        }
    }
}
