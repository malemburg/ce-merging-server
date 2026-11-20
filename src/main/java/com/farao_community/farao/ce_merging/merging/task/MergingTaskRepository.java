/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.farao_community.farao.ce_merging.merging.task;

import com.farao_community.farao.ce_merging.merging.task.entities.MergingTask;
import com.farao_community.farao.ce_merging.merging.task.enums.TaskStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MergingTaskRepository extends CrudRepository<MergingTask, Long> {

    @Modifying
    @Query("update MergingTask t set t.status = :newStatus, t.statusDetail = :statusDetail " +
           "where t.status = :oldStatus")
    int updateStatus(@Param("oldStatus") TaskStatus oldStatus,
                     @Param("newStatus") TaskStatus newStatus,
                     @Param("statusDetail") String statusDetail);
}
