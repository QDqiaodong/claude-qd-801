package com.concrete.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 一条已执行的改派：任务从原主机迁到接替主机，计划时段保持不变。
 */
@Getter
@Setter
public class ReassignChangeVO {

    private Long orderId;
    private String orderNo;
    private String projectName;
    private String strengthGrade;
    private BigDecimal plannedVolume;

    private Long sourceMixerId;
    private String sourceMixerName;
    private Long targetMixerId;
    private String targetMixerName;

    /** 沿用原计划时段（确认前后逐字段一致） */
    private LocalDateTime plannedStart;
    private LocalDateTime plannedEnd;
}
