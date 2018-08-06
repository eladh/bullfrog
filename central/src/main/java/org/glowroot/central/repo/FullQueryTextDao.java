/*
 * Copyright 2016-2018 the original author or authors.
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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.repo;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.util.MoreFutures;
import org.glowroot.central.util.RateLimiter;
import org.glowroot.central.util.Session;
import org.glowroot.common.util.Styles;
import org.glowroot.common2.config.CentralStorageConfig;
import org.glowroot.common2.repo.ConfigRepository.RollupConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

class FullQueryTextDao {

    private static final Logger logger = LoggerFactory.getLogger(FullQueryTextDao.class);

    private final Session session;
    private final ConfigRepositoryImpl configRepository;

    private final PreparedStatement insertCheckV2PS;
    private final PreparedStatement readCheckV2PS;
    private final PreparedStatement readCheckV1PS;

    private final PreparedStatement insertPS;
    private final PreparedStatement readPS;
    private final PreparedStatement readTtlPS;

    private final RateLimiter<FullQueryTextKey> rateLimiter = new RateLimiter<>(100000);
    private final RateLimiter<String> rateLimiterForSha1 = new RateLimiter<>(10000);

    FullQueryTextDao(Session session, ConfigRepositoryImpl configRepository) throws Exception {
        this.session = session;
        this.configRepository = configRepository;

        session.createTableWithSTCS("create table if not exists full_query_text_check (agent_rollup"
                + " varchar, full_query_text_sha1 varchar, primary key (agent_rollup,"
                + " full_query_text_sha1))");
        session.createTableWithSTCS("create table if not exists full_query_text_check_v2"
                + " (agent_rollup varchar, full_query_text_sha1 varchar, primary key"
                + " ((agent_rollup, full_query_text_sha1)))");
        session.createTableWithSTCS("create table if not exists full_query_text"
                + " (full_query_text_sha1 varchar, full_query_text varchar, primary key"
                + " (full_query_text_sha1))");

        insertCheckV2PS = session.prepare("insert into full_query_text_check_v2 (agent_rollup,"
                + " full_query_text_sha1) values (?, ?) using ttl ?");
        readCheckV2PS = session.prepare("select agent_rollup from full_query_text_check_v2 where"
                + " agent_rollup = ? and full_query_text_sha1 = ?");
        readCheckV1PS = session.prepare("select agent_rollup from full_query_text_check where"
                + " agent_rollup = ? and full_query_text_sha1 = ?");

        insertPS = session.prepare("insert into full_query_text (full_query_text_sha1,"
                + " full_query_text) values (?, ?) using ttl ?");
        readPS = session.prepare(
                "select full_query_text from full_query_text where full_query_text_sha1 = ?");
        readTtlPS = session.prepare(
                "select TTL(full_query_text) from full_query_text where full_query_text_sha1 = ?");
    }

    @Nullable
    String getFullText(String agentRollupId, String fullTextSha1) throws Exception {
        String fullText = getFullTextUsingPS(agentRollupId, fullTextSha1, readCheckV2PS);
        if (fullText != null) {
            return fullText;
        }
        return getFullTextUsingPS(agentRollupId, fullTextSha1, readCheckV1PS);
    }

    List<Future<?>> store(String agentId, String fullTextSha1, String fullText) throws Exception {
        FullQueryTextKey rateLimiterKey = ImmutableFullQueryTextKey.of(agentId, fullTextSha1);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        Future<?> future = storeCheckInternal(rateLimiterKey);
        if (!rateLimiterForSha1.tryAcquire(fullTextSha1)) {
            return ImmutableList.of(future);
        }
        CompletableFuture<?> future2;
        try {
            future2 = storeInternal(rateLimiterKey.fullTextSha1(), fullText);
        } catch (Exception e) {
            invalidateBoth(rateLimiterKey);
            throw e;
        }
        return ImmutableList.of(future,
                MoreFutures.onFailure(future2, () -> invalidateBoth(rateLimiterKey)));
    }

    List<Future<?>> updateTTL(String agentRollupId, String fullTextSha1) throws Exception {
        FullQueryTextKey rateLimiterKey = ImmutableFullQueryTextKey.of(agentRollupId, fullTextSha1);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        Future<?> future = storeCheckInternal(rateLimiterKey);
        if (!rateLimiterForSha1.tryAcquire(fullTextSha1)) {
            return ImmutableList.of(future);
        }
        ListenableFuture<ResultSet> readFuture;
        try {
            BoundStatement boundStatement = readPS.bind();
            boundStatement.setString(0, fullTextSha1);
            readFuture = session.executeAsync(boundStatement);
        } catch (Exception e) {
            invalidateBoth(rateLimiterKey);
            throw e;
        }
        CompletableFuture</*@Nullable*/ Void> chainedFuture = new CompletableFuture<>();
        Futures.addCallback(readFuture, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet results) {
                Row row = results.one();
                if (row == null) {
                    // this shouldn't happen any more now that full query text insert futures are
                    // waited on prior to inserting aggregate/trace records with sha1
                    logger.warn("full query text record not found for sha1: {}", fullTextSha1);
                    chainedFuture.complete(null);
                    return;
                }
                String fullText = checkNotNull(row.getString(0));
                try {
                    CompletableFuture<?> future =
                            storeInternal(rateLimiterKey.fullTextSha1(), fullText);
                    future.whenComplete((result, t) -> {
                        if (t != null) {
                            chainedFuture.completeExceptionally(t);
                        } else {
                            chainedFuture.complete(null);
                        }
                    });
                } catch (Exception e) {
                    logger.debug(e.getMessage(), e);
                    chainedFuture.completeExceptionally(e);
                }
            }
            @Override
            public void onFailure(Throwable t) {
                logger.debug(t.getMessage(), t);
                chainedFuture.completeExceptionally(t);
            }
        }, MoreExecutors.directExecutor());
        return ImmutableList.of(future,
                MoreFutures.onFailure(chainedFuture, () -> invalidateBoth(rateLimiterKey)));
    }

    List<Future<?>> updateCheckTTL(String agentRollupId, String fullTextSha1) throws Exception {
        FullQueryTextKey rateLimiterKey = ImmutableFullQueryTextKey.of(agentRollupId, fullTextSha1);
        if (!rateLimiter.tryAcquire(rateLimiterKey)) {
            return ImmutableList.of();
        }
        return ImmutableList.of(storeCheckInternal(rateLimiterKey));
    }

    private @Nullable String getFullTextUsingPS(String agentRollupId, String fullTextSha1,
            PreparedStatement readCheckPS) throws Exception {
        BoundStatement boundStatement = readCheckPS.bind();
        boundStatement.setString(0, agentRollupId);
        boundStatement.setString(1, fullTextSha1);
        ResultSet results = session.execute(boundStatement);
        if (results.isExhausted()) {
            return null;
        }
        boundStatement = readPS.bind();
        boundStatement.setString(0, fullTextSha1);
        results = session.execute(boundStatement);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        return row.getString(0);
    }

    private CompletableFuture<?> storeInternal(String fullTextSha1, String fullText)
            throws Exception {
        BoundStatement boundStatement = readTtlPS.bind();
        boundStatement.setString(0, fullTextSha1);
        ListenableFuture<ResultSet> future2 = session.executeAsync(boundStatement);

        CompletableFuture</*@Nullable*/ Void> chainedFuture = new CompletableFuture<>();

        Futures.addCallback(future2, new FutureCallback<ResultSet>() {
            @Override
            public void onSuccess(ResultSet results) {
                try {
                    Row row = results.one();
                    int ttl = getTTL();
                    if (row == null) {
                        insertAndCompleteFuture(ttl);
                    } else {
                        int existingTTL = row.getInt(0);
                        if (existingTTL < ttl && existingTTL != 0) {
                            insertAndCompleteFuture(ttl);
                        } else {
                            chainedFuture.complete(null);
                        }
                    }
                } catch (Exception e) {
                    logger.debug(e.getMessage(), e);
                    chainedFuture.completeExceptionally(e);
                }
            }
            @Override
            public void onFailure(Throwable t) {
                logger.debug(t.getMessage(), t);
                chainedFuture.completeExceptionally(t);
            }
            private void insertAndCompleteFuture(int ttl) throws Exception {
                try {
                    BoundStatement boundStatement = insertPS.bind();
                    int i = 0;
                    boundStatement.setString(i++, fullTextSha1);
                    boundStatement.setString(i++, fullText);
                    boundStatement.setInt(i++, ttl);
                    ListenableFuture<ResultSet> future = session.executeAsync(boundStatement);
                    Futures.addCallback(future, new FutureCallback<ResultSet>() {
                        @Override
                        public void onSuccess(ResultSet results) {
                            chainedFuture.complete(null);
                        }
                        @Override
                        public void onFailure(Throwable t) {
                            logger.debug(t.getMessage(), t);
                            chainedFuture.completeExceptionally(t);
                        }
                    }, MoreExecutors.directExecutor());
                } catch (Exception e) {
                    logger.debug(e.getMessage(), e);
                    chainedFuture.completeExceptionally(e);
                }
            }
        }, MoreExecutors.directExecutor());
        return chainedFuture;
    }

    private CompletableFuture<?> storeCheckInternal(FullQueryTextKey rateLimiterKey)
            throws Exception {
        try {
            BoundStatement boundStatement = insertCheckV2PS.bind();
            int i = 0;
            boundStatement.setString(i++, rateLimiterKey.agentRollupId());
            boundStatement.setString(i++, rateLimiterKey.fullTextSha1());
            boundStatement.setInt(i++, getTTL());
            ListenableFuture<?> future = session.executeAsync(boundStatement);
            return MoreFutures.onFailure(future, () -> rateLimiter.invalidate(rateLimiterKey));
        } catch (Exception e) {
            rateLimiter.invalidate(rateLimiterKey);
            throw e;
        }
    }

    private void invalidateBoth(FullQueryTextKey rateLimiterKey) {
        rateLimiter.invalidate(rateLimiterKey);
        rateLimiterForSha1.invalidate(rateLimiterKey.fullTextSha1());
    }

    private int getTTL() throws Exception {
        CentralStorageConfig storageConfig = configRepository.getCentralStorageConfig();
        int queryRollupExpirationHours =
                Iterables.getLast(storageConfig.queryAndServiceCallRollupExpirationHours());
        int expirationHours =
                Math.max(queryRollupExpirationHours, storageConfig.traceExpirationHours());
        List<RollupConfig> rollupConfigs = configRepository.getRollupConfigs();
        RollupConfig lastRollupConfig = Iterables.getLast(rollupConfigs);
        // adding largest rollup interval to account for query being retained longer by rollups
        long ttl = MILLISECONDS.toSeconds(lastRollupConfig.intervalMillis())
                // adding 1 day to account for rateLimiter
                + DAYS.toSeconds(1)
                + HOURS.toSeconds(expirationHours);
        return Ints.saturatedCast(ttl);
    }

    @Value.Immutable
    @Styles.AllParameters
    interface FullQueryTextKey {
        String agentRollupId();
        String fullTextSha1();
    }
}
