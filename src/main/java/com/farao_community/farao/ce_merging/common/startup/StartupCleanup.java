/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.farao_community.farao.ce_merging.common.startup;

import com.farao_community.farao.ce_merging.daily_merging.DailyMergingRepository;
import com.farao_community.farao.ce_merging.merging.post_process.merging_supervisor_logs.LoggingEventRepository;
import com.farao_community.farao.ce_merging.merging.task.MergingTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus.ERROR;
import static com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus.RUNNING;

/**
 * Cleans up the database once at every start of the server, before it accepts
 * requests (Spring Boot starts the web server's connectors only after all
 * singletons are instantiated):
 * <ul>
 *     <li>Tasks still RUNNING were interrupted by the restart, since no run of
 *     this server is going yet. They are set to ERROR; left RUNNING, they
 *     would refuse every new run and download for good.</li>
 *     <li>The execution logs are kept across restarts, and their records are
 *     not deleted with their task: the records older than the configured
 *     retention are removed. A retention of 0 days or less keeps them all.</li>
 * </ul>
 * A failure stops the server from starting.
 */
@Component
public class StartupCleanup implements SmartInitializingSingleton {

    static final String INTERRUPTED_TASK_DETAIL = "Interrupted by a restart of the server";

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupCleanup.class);

    private final MergingTaskRepository mergingTaskRepository;
    private final DailyMergingRepository dailyMergingRepository;
    private final LoggingEventRepository loggingEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final long logRetentionDays;

    public StartupCleanup(final MergingTaskRepository mergingTaskRepository,
                          final DailyMergingRepository dailyMergingRepository,
                          final LoggingEventRepository loggingEventRepository,
                          final PlatformTransactionManager transactionManager,
                          @Value("${ce-merging-server.logs.retention-days:30}") final long logRetentionDays) {
        this.mergingTaskRepository = mergingTaskRepository;
        this.dailyMergingRepository = dailyMergingRepository;
        this.loggingEventRepository = loggingEventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.logRetentionDays = logRetentionDays;
    }

    @Override
    public void afterSingletonsInstantiated() {
        failInterruptedTasks();
        removeLogsOlderThanRetention(Instant.now());
    }

    void failInterruptedTasks() {
        transactionTemplate.executeWithoutResult(status -> {
            final int tasks = mergingTaskRepository.updateStatus(RUNNING, ERROR, INTERRUPTED_TASK_DETAIL);
            final int dailyTasks = dailyMergingRepository.updateStatus(RUNNING, ERROR);
            if (tasks > 0 || dailyTasks > 0) {
                LOGGER.warn("Set {} merging task(s) and {} daily merging task(s) interrupted by a restart to {}",
                            tasks, dailyTasks, ERROR);
            }
        });
    }

    void removeLogsOlderThanRetention(final Instant now) {
        if (logRetentionDays <= 0) {
            LOGGER.info("Execution log retention is off: keeping all log records");
            return;
        }
        final long cutoff = now.minus(Duration.ofDays(logRetentionDays)).toEpochMilli();
        transactionTemplate.executeWithoutResult(status -> {
            loggingEventRepository.deletePropertiesOfLogsBefore(cutoff);
            loggingEventRepository.deleteExceptionsOfLogsBefore(cutoff);
            final int logs = loggingEventRepository.deleteLogsBefore(cutoff);
            if (logs > 0) {
                LOGGER.info("Removed {} log record(s) older than {} days", logs, logRetentionDays);
            }
        });
    }
}
