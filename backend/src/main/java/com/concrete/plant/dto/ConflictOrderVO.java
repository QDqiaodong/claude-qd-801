package com.concrete.plant.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 接替主机在任务原计划时段上的占用情况。
 */
@Getter
@Setter
public class ConflictOrderVO {

    private Long orderId;
    private String orderNo;
    private String projectName;
    private String status;
    private String statusName;
    private LocalDateTime plannedStart;
    private LocalDateTime plannedEnd;
}
