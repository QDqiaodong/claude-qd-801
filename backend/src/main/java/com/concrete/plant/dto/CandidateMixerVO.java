package com.concrete.plant.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 接替主机候选：只有非故障、非检修的主机才会出现；conflictOrder 非空表示该时段已有任务。
 */
@Getter
@Setter
public class CandidateMixerVO {

    private Long mixerId;
    private String mixerCode;
    private String mixerName;
    private String model;
    private String status;
    private String statusName;
    private String plantName;

    /** 该主机在任务原时段是否空闲（空闲才可被选为接替主机） */
    private boolean freeInSlot;

    /** 占用该时段的冲突任务；freeInSlot=true 时为 null */
    private ConflictOrderVO conflictOrder;
}
