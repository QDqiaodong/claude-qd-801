package com.concrete.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * 可改派任务：尚未开工（PENDING）且没有任何发车记录。
 * 改派必须沿用原计划时段，candidateMixers 给出该时段可用且空闲的候选主机及冲突情况。
 */
@Getter
@Setter
public class ReassignableOrderVO {

    private Long orderId;
    private String orderNo;
    private String projectName;
    private String pourPart;
    private String strengthGrade;
    private BigDecimal plannedVolume;
    private LocalDateTime plannedStart;
    private LocalDateTime plannedEnd;

    /** 原主机快照（故障前） */
    private Long sourceMixerId;
    private String sourceMixerName;

    /** 该任务原计划时段可用的接替主机候选（非故障、非检修、非故障主机本身） */
    private List<CandidateMixerVO> candidates = new ArrayList<>();

    /** 确认时回显调度员选择的接替主机，便于确认页核对 */
    private Long selectedMixerId;
}
