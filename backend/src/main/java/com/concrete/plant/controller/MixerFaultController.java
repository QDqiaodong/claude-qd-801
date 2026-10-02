package com.concrete.plant.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.concrete.plant.common.ApiResponse;
import com.concrete.plant.dto.FaultConfirmVO;
import com.concrete.plant.dto.FaultPreviewVO;
import com.concrete.plant.dto.FaultRequest;
import com.concrete.plant.service.FaultService;

import jakarta.validation.Valid;

/**
 * 主机临时故障停机处置：
 * POST /preview 只读预演（受影响任务、不可改派说明、接替主机排期冲突）；
 * POST /confirm 确认时重新核对全部条件，整批原子执行，任一不符全量回滚。
 */
@RestController
@RequestMapping("/api/mixer-faults")
public class MixerFaultController {

    private final FaultService faultService;

    public MixerFaultController(FaultService faultService) {
        this.faultService = faultService;
    }

    /** 预演：不改任何数据，只给受影响任务与改派方案校验结果 */
    @PostMapping("/preview")
    public ApiResponse<FaultPreviewVO> preview(@Valid @RequestBody FaultRequest request) {
        return ApiResponse.success("预演完成，尚未改动任何数据", faultService.preview(request));
    }

    /** 确认：同一次业务操作内置故障 + 存处置记录 + 全部合格任务改派，失败整批回滚 */
    @PostMapping("/confirm")
    public ApiResponse<FaultConfirmVO> confirm(@Valid @RequestBody FaultRequest request) {
        return ApiResponse.success("故障停机已确认，合格任务已全部改派", faultService.confirm(request));
    }
}
