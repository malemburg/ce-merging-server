/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.farao_community.farao.ce_merging.common.startup;

import com.farao_community.farao.ce_merging.daily_merging.DailyMergingRepository;
import com.farao_community.farao.ce_merging.daily_merging.entities.DailyMergingTask;
import com.farao_community.farao.ce_merging.merging.post_process.merging_supervisor_logs.LoggingEventRepository;
import com.farao_community.farao.ce_merging.merging.task.MergingTaskRepository;
import com.farao_community.farao.ce_merging.merging.task.entities.MergingTask;
import com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.farao_community.farao.ce_merging.common.startup.StartupCleanup.INTERRUPTED_TASK_DETAIL;
import static com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus.CREATED;
import static com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus.ERROR;
import static com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus.RUNNING;
import static com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class StartupCleanupTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final long OLD_LOG_ID = 990_001L;
    private static final long RECENT_LOG_ID = 990_002L;

    @Autowired
    private StartupCleanup startupCleanup;
    @Autowired
    private MergingTaskRepository mergingTaskRepository;
    @Autowired
    private DailyMergingRepository dailyMergingRepository;
    @Autowired
    private LoggingEventRepository loggingEventRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> taskIds = new ArrayList<>();
    private final List<Long> dailyTaskIds = new ArrayList<>();

    @AfterEach
    void removeTestData() {
        mergingTaskRepository.deleteAllById(taskIds);
        dailyMergingRepository.deleteAllById(dailyTaskIds);
        for (final long id : List.of(OLD_LOG_ID, RECENT_LOG_ID)) {
            jdbcTemplate.update("DELETE FROM logging_event_property WHERE event_id = ?", id);
            jdbcTemplate.update("DELETE FROM logging_event_exception WHERE event_id = ?", id);
            jdbcTemplate.update("DELETE FROM logging_event WHERE event_id = ?", id);
        }
    }

    @Test
    void shouldFailOnlyTheRunningTasks() {
        final long running = saveTask(RUNNING);
        final long created = saveTask(CREATED);
        final long succeeded = saveTask(SUCCESS);
        final long dailyRunning = saveDailyTask(RUNNING);
        final long dailySucceeded = saveDailyTask(SUCCESS);

        startupCleanup.failInterruptedTasks();

        final MergingTask interrupted = mergingTaskRepository.findById(running).orElseThrow();
        assertEquals(ERROR, interrupted.getStatus());
        assertEquals(INTERRUPTED_TASK_DETAIL, interrupted.getStatusDetail());
        assertEquals(CREATED, mergingTaskRepository.findById(created).orElseThrow().getStatus());
        assertNull(mergingTaskRepository.findById(created).orElseThrow().getStatusDetail());
        assertEquals(SUCCESS, mergingTaskRepository.findById(succeeded).orElseThrow().getStatus());
        assertEquals(ERROR, dailyMergingRepository.findById(dailyRunning).orElseThrow().getTaskStatus());
        assertEquals(SUCCESS, dailyMergingRepository.findById(dailySucceeded).orElseThrow().getTaskStatus());
    }

    @Test
    void shouldRemoveOnlyTheLogsOlderThanTheRetention() {
        insertLog(OLD_LOG_ID, NOW.minus(Duration.ofDays(30)).minusMillis(1));
        insertLog(RECENT_LOG_ID, NOW.minus(Duration.ofDays(30)));

        startupCleanup.removeLogsOlderThanRetention(NOW);

        assertEquals(0, countRows(OLD_LOG_ID));
        assertEquals(3, countRows(RECENT_LOG_ID));
    }

    @Test
    void shouldKeepAllLogsWithoutRetention() {
        insertLog(OLD_LOG_ID, NOW.minus(Duration.ofDays(3650)));
        final StartupCleanup keepAll = new StartupCleanup(mergingTaskRepository, dailyMergingRepository,
                                                          loggingEventRepository, transactionManager, 0);

        keepAll.removeLogsOlderThanRetention(NOW);

        assertEquals(3, countRows(OLD_LOG_ID));
    }

    private long saveTask(final TaskStatus status) {
        final MergingTask task = new MergingTask();
        task.setStatus(status);
        final long id = mergingTaskRepository.save(task).getId();
        taskIds.add(id);
        return id;
    }

    private long saveDailyTask(final TaskStatus status) {
        final DailyMergingTask task = new DailyMergingTask();
        task.setTaskStatus(status);
        final long id = dailyMergingRepository.save(task).getId();
        dailyTaskIds.add(id);
        return id;
    }

    /**
     * Inserts a log record with one property and one exception line, as
     * logback's DBAppender writes them.
     */
    private void insertLog(final long eventId, final Instant time) {
        jdbcTemplate.update("INSERT INTO logging_event (event_id, timestmp, formatted_message, logger_name, "
                            + "level_string, reference_flag) VALUES (?, ?, 'message', 'logger', 'INFO', 0)",
                            eventId, time.toEpochMilli());
        jdbcTemplate.update("INSERT INTO logging_event_property (event_id, mapped_key, mapped_value) "
                            + "VALUES (?, 'taskId', '1')", eventId);
        jdbcTemplate.update("INSERT INTO logging_event_exception (event_id, i, trace_line) "
                            + "VALUES (?, 0, 'trace')", eventId);
    }

    private int countRows(final long eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM logging_event WHERE event_id = ?",
                                           Integer.class, eventId)
               + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM logging_event_property WHERE event_id = ?",
                                             Integer.class, eventId)
               + jdbcTemplate.queryForObject("SELECT COUNT(*) FROM logging_event_exception WHERE event_id = ?",
                                             Integer.class, eventId);
    }
}
