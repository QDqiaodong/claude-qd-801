package com.concrete.plant.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 故障停机改派预演（只读，不写任何数据）：
 * 可改派任务逐张给出候选接替主机与时段冲突；不可改派任务逐条给出原因。
 */
@Getter
@Setter
public class FaultPreviewVO {

    private Long mixerId;
    private String mixerCode;
    private String mixerName;
    private String statusBefore;
    private String statusBeforeName;
    private String faultReason;
    private LocalDateTime occurredAt;

    /** 可改派任务（PENDING 且无发车记录） */
    private List<ReassignableOrderVO> reassignable = new ArrayList<>();

    /** 不可改派任务（生产中 / 已完成 / 已有发车记录） */
    private List<BlockedOrderVO> blocked = new ArrayList<>();

    /** 该主机名下任务总数 = 可改派 + 不可改派 */
    private int affectedTotal;
}
