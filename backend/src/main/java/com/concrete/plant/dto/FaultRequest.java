package com.concrete.plant.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 故障停机预演 / 确认请求。
 * assignments 只在确认时必须：每张可改派任务显式指定一台接替主机（沿用原计划时段）。
 */
@Getter
@Setter
public class FaultRequest {

    @NotNull(message = "必须选择故障主机")
    private Long mixerId;

    @NotBlank(message = "故障原因不能为空")
    private String faultReason;

    @NotNull(message = "故障发生时间不能为空")
    private LocalDateTime occurredAt;

    /** 确认时的改派清单：orderId -> targetMixerId；预演不传 */
    private java.util.List<ReassignItem> assignments;

    private String remark;

    @Getter
    @Setter
    public static class ReassignItem {
        @NotNull(message = "改派清单缺少任务单 ID")
        private Long orderId;

        @NotNull(message = "每张可改派任务必须选择一台接替主机")
        private Long targetMixerId;
    }
}
