package com.concrete.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 不可改派任务：生产中 / 已完成 / 已有发车记录，单独列明原因，不允许随待排任务迁走。
 */
@Getter
@Setter
public class BlockedOrderVO {

    private Long orderId;
    private String orderNo;
    private String projectName;
    private String pourPart;
    private String strengthGrade;
    private BigDecimal plannedVolume;
    private String status;
    private String statusName;
    private LocalDateTime plannedStart;
    private LocalDateTime plannedEnd;

    /** 不可改派原因码 PRODUCING / DONE / HAS_TICKET */
    private String reason;
    /** 不可改派说明（人话） */
    private String reasonText;
    /** 已有发车记录条数（HAS_TICKET 时给出） */
    private Long ticketCount;
}
