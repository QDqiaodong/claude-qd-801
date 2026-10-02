package com.concrete.plant.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 故障停机确认结果：同一次业务操作中原子完成
 * ① 原主机置为 FAULT ② 处置记录落库 ③ 全部合格任务主机改派。
 * 任一项失败整批回滚，调用方不会拿到半成功结果。
 */
@Getter
@Setter
public class FaultConfirmVO {

    private Long recordId;
    private String recordNo;

    private Long mixerId;
    private String mixerCode;
    private String mixerName;
    private String statusBefore;
    private String statusBeforeName;
    private String statusAfter;
    private String statusAfterName;
    private String faultReason;
    private LocalDateTime occurredAt;
    private LocalDateTime handledAt;

    /** 本次实际改派的任务（逐项展示变化） */
    private List<ReassignChangeVO> changes = new ArrayList<>();

    /** 确认时仍然不可改派的任务（生产中 / 已完成 / 已有发车记录），保持在原主机 */
    private List<BlockedOrderVO> blocked = new ArrayList<>();

    private int affectedTotal;
    private int reassignedCount;
    private int blockedCount;
}
