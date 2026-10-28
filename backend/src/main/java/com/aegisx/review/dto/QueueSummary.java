package com.aegisx.review.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregate statistics for the compliance review queue.
 *
 * <p>This used to be a nested class in the controller, computed by materialising every queue item
 * ({@code PageRequest.of(0, Integer.MAX_VALUE)}) and counting in Java. The numbers are now produced
 * by aggregate queries, so the cost no longer grows with the size of the queue. The JSON shape is
 * unchanged.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueSummary {
    private int totalItems;
    private int highPriorityItems;
    private int criticalViolations;
    private long oldestItemHours;
}
