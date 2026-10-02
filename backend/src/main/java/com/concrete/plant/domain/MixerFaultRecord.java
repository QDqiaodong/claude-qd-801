package com.concrete.plant.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 主机故障停机处置记录：一次确认 = 置故障 + 本记录落库 + 全部合格任务改派，同事务完成。
 */
@Entity
@Table(name = "mixer_fault_record")
@BatchSize(size = 50)
@Getter
@Setter
public class MixerFaultRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_no", nullable = false, unique = true, length = 32)
    private String recordNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mixer_id", nullable = false)
    private Mixer mixer;

    /** 停机时主机原状态 RUNNING / IDLE，留痕便于事后核对 */
    @Column(name = "previous_status", nullable = false, length = 16)
    private String previousStatus;

    @Column(name = "fault_reason", nullable = false, length = 255)
    private String faultReason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "handled_at", nullable = false)
    private LocalDateTime handledAt;

    /** 受影响任务总数（该主机名下全部未完成任务单） */
    @Column(name = "affected_count", nullable = false)
    private Integer affectedCount;

    /** 本次实际改派任务数 */
    @Column(name = "reassigned_count", nullable = false)
    private Integer reassignedCount;

    /** 不可改派任务数（生产中 / 已完成 / 已有发车记录） */
    @Column(name = "blocked_count", nullable = false)
    private Integer blockedCount;

    /** CONFIRMED：确认时校验全部通过；失败的批次整体回滚，不会留下记录 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "remark", length = 255)
    private String remark;
}
