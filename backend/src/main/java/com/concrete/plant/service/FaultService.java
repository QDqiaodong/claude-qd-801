package com.concrete.plant.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.concrete.plant.common.BizException;
import com.concrete.plant.common.Dict;
import com.concrete.plant.domain.Mixer;
import com.concrete.plant.domain.MixerFaultRecord;
import com.concrete.plant.domain.ProductionOrder;
import com.concrete.plant.dto.BlockedOrderVO;
import com.concrete.plant.dto.CandidateMixerVO;
import com.concrete.plant.dto.ConflictOrderVO;
import com.concrete.plant.dto.FaultConfirmVO;
import com.concrete.plant.dto.FaultPreviewVO;
import com.concrete.plant.dto.FaultRequest;
import com.concrete.plant.dto.ReassignChangeVO;
import com.concrete.plant.dto.ReassignableOrderVO;
import com.concrete.plant.repository.DispatchTicketRepository;
import com.concrete.plant.repository.MixerFaultRecordRepository;
import com.concrete.plant.repository.MixerRepository;
import com.concrete.plant.repository.ProductionOrderRepository;

/**
 * 主机临时故障停机处置：
 *
 * <p>预演 {@link #preview} 为只读操作，只列出受影响任务、改派候选与时段冲突，不写任何数据；
 * 确认 {@link #confirm} 在【写任何数据之前】按当前数据库状态重新核对任务阶段、发车记录、
 * 接替主机状态与排期（含本批次内互撞），任一任务不再符合预演条件即整批拒绝；
 * 通过后在同一次事务内完成「原主机置故障 + 处置记录落库 + 全部任务改派」，失败整体回滚。
 */
@Service
public class FaultService {

    /** 故障发生时间允许比服务器时间快的宽限，避免表单时钟误差误拒 */
    private static final long FUTURE_TOLERANCE_SECONDS = 60;

    private static final String DASHBOARD_CACHE_KEY = "concrete:plant:dashboard";

    private final MixerRepository mixerRepository;
    private final ProductionOrderRepository orderRepository;
    private final DispatchTicketRepository ticketRepository;
    private final MixerFaultRecordRepository faultRecordRepository;
    private final StringRedisTemplate redis;

    public FaultService(MixerRepository mixerRepository, ProductionOrderRepository orderRepository,
                        DispatchTicketRepository ticketRepository,
                        MixerFaultRecordRepository faultRecordRepository, StringRedisTemplate redis) {
        this.mixerRepository = mixerRepository;
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.faultRecordRepository = faultRecordRepository;
        this.redis = redis;
    }

    // ========================= 预演（只读，不落任何数据） =========================

    @Transactional(readOnly = true)
    public FaultPreviewVO preview(FaultRequest request) {
        validateBase(request);
        Mixer faultMixer = requireFaultableMixer(request.getMixerId());
        LocalDateTime occurredAt = request.getOccurredAt();
        validateOccurredAt(occurredAt);

        FaultPreviewVO vo = new FaultPreviewVO();
        vo.setMixerId(faultMixer.getId());
        vo.setMixerCode(faultMixer.getMixerCode());
        vo.setMixerName(faultMixer.getName());
        vo.setStatusBefore(faultMixer.getStatus());
        vo.setStatusBeforeName(Dict.mixerStatusName(faultMixer.getStatus()));
        vo.setFaultReason(request.getFaultReason().trim());
        vo.setOccurredAt(occurredAt);

        List<Mixer> candidates = eligibleReplacementMixers(faultMixer.getId());

        for (ProductionOrder order : orderRepository.findByMixer(faultMixer.getId())) {
            long ticketCount = ticketRepository.countByOrderId(order.getId());
            String blockedReason = classifyBlocked(order, ticketCount);
            if (blockedReason != null) {
                vo.getBlocked().add(toBlockedVO(order, blockedReason, ticketCount));
                continue;
            }
            vo.getReassignable().add(toReassignableVO(order, faultMixer, candidates));
        }
        vo.getReassignable().sort(Comparator.comparing(ReassignableOrderVO::getPlannedStart,
                Comparator.nullsLast(Comparator.naturalOrder())));
        vo.setAffectedTotal(vo.getReassignable().size() + vo.getBlocked().size());
        return vo;
    }

    // ========================= 确认（整批核对通过后原子落库） =========================

    @Transactional
    public FaultConfirmVO confirm(FaultRequest request) {
        // ---- 1. 基础校验与预演一致 ----
        validateBase(request);
        Mixer faultMixer = requireFaultableMixer(request.getMixerId());
        LocalDateTime occurredAt = request.getOccurredAt();
        validateOccurredAt(occurredAt);
        String faultReason = request.getFaultReason().trim();

        // ---- 2. 不相信任何前端快照：重新拉出该主机全部任务并重新分类 ----
        List<ProductionOrder> dbOrders = orderRepository.findByMixer(faultMixer.getId());
        List<ProductionOrder> reassignable = new ArrayList<>();
        List<BlockedOrderVO> blockedVOs = new ArrayList<>();
        for (ProductionOrder order : dbOrders) {
            long ticketCount = ticketRepository.countByOrderId(order.getId());
            String blockedReason = classifyBlocked(order, ticketCount);
            if (blockedReason != null) {
                blockedVOs.add(toBlockedVO(order, blockedReason, ticketCount));
            } else {
                reassignable.add(order);
            }
        }

        // ---- 3. 改派清单核对：每张可改派任务必须显式指定一台接替主机 ----
        List<FaultRequest.ReassignItem> assignments = request.getAssignments();
        if (assignments == null) {
            assignments = List.of();
        }
        if (assignments.size() != reassignable.size()) {
            throw new BizException("改派清单与可改派任务不一致：可改派 " + reassignable.size()
                    + " 张，清单提交 " + assignments.size() + " 张，整批确认已中止，未改动任何数据");
        }

        Map<Long, ProductionOrder> reassignableMap = new LinkedHashMap<>();
        for (ProductionOrder o : reassignable) {
            reassignableMap.put(o.getId(), o);
        }

        // 接替主机 id -> 该主机本批承接的任务（用于批次内时段互撞检查）
        Map<Long, List<ProductionOrder>> targetBuckets = new LinkedHashMap<>();
        // orderId -> 最终接替主机（已通过状态/排期核对）
        Map<Long, Mixer> chosenTargets = new LinkedHashMap<>();

        for (FaultRequest.ReassignItem item : assignments) {
            if (item == null || item.getOrderId() == null) {
                throw new BizException("改派清单存在空条目，整批确认已中止，未改动任何数据");
            }
            ProductionOrder order = reassignableMap.get(item.getOrderId());
            if (order == null) {
                throw new BizException("任务单 " + item.getOrderId()
                        + " 已不属于可改派范围（可能已开工或已产生发车记录），整批确认已中止，未改动任何数据");
            }
            Long targetId = item.getTargetMixerId();
            if (targetId == null) {
                throw new BizException("任务单 " + order.getOrderNo() + " 未选择接替主机，整批确认已中止，未改动任何数据");
            }
            if (targetId.equals(faultMixer.getId())) {
                throw new BizException("任务单 " + order.getOrderNo()
                        + " 不能改派给故障主机本身，整批确认已中止，未改动任何数据");
            }
            Mixer target = mixerRepository.findById(targetId)
                    .orElseThrow(() -> new BizException("接替主机不存在：id=" + targetId
                            + "，整批确认已中止，未改动任何数据"));
            // 3.1 重新核对接替主机：必须非故障、非检修
            if (!"RUNNING".equals(target.getStatus()) && !"IDLE".equals(target.getStatus())) {
                throw new BizException("接替主机 " + target.getName() + " 当前状态为「"
                        + Dict.mixerStatusName(target.getStatus())
                        + "」，不可承接任务，整批确认已中止，未改动任何数据");
            }
            // 3.2 重新核对目标主机在原时段的现有排期：有冲突必须整批停止
            List<ProductionOrder> conflicts = orderRepository.findOverlapping(
                    target.getId(), order.getId(), order.getPlannedStart(), order.getPlannedEnd());
            if (!conflicts.isEmpty()) {
                ProductionOrder c = conflicts.get(0);
                throw new BizException("任务单 " + order.getOrderNo() + " 沿用原计划时段 "
                        + slotText(order) + " 改派到主机 " + target.getName()
                        + " 时，与该主机已有任务 " + c.getOrderNo() + "（" + c.getProjectName() + "，"
                        + slotText(c) + "）时段冲突，整批确认已中止，未改动任何数据");
            }
            chosenTargets.put(order.getId(), target);
            targetBuckets.computeIfAbsent(targetId, k -> new ArrayList<>()).add(order);
        }

        // 3.3 重新核对可改派集合与清单一一对应（防重复、防遗漏）
        if (chosenTargets.size() != reassignable.size()) {
            throw new BizException("改派清单存在重复或遗漏的任务单，整批确认已中止，未改动任何数据");
        }
        // 3.4 批次内互撞：同一接替主机承接的多张任务，沿用原时段后不得互相重叠
        for (Map.Entry<Long, List<ProductionOrder>> bucket : targetBuckets.entrySet()) {
            List<ProductionOrder> list = bucket.getValue();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    ProductionOrder a = list.get(i);
                    ProductionOrder b = list.get(j);
                    if (overlaps(a, b)) {
                        Mixer t = chosenTargets.get(a.getId());
                        throw new BizException("任务单 " + a.getOrderNo() + " 与 " + b.getOrderNo()
                                + " 原计划时段重叠，不能同时改派到主机 " + t.getName()
                                + "，整批确认已中止，未改动任何数据");
                    }
                }
            }
        }

        // ---- 4. 全部核对通过，以下在同一事务内原子写入 ----
        String previousStatus = faultMixer.getStatus();
        List<ReassignChangeVO> changes = new ArrayList<>();
        // 4.1 全部合格任务改派（沿用原计划时段，不触碰任何时间字段）
        for (ProductionOrder order : reassignable) {
            Mixer target = chosenTargets.get(order.getId());
            Mixer source = order.getMixer();
            order.setMixer(target);
            orderRepository.save(order);

            ReassignChangeVO change = new ReassignChangeVO();
            change.setOrderId(order.getId());
            change.setOrderNo(order.getOrderNo());
            change.setProjectName(order.getProjectName());
            change.setStrengthGrade(order.getStrengthGrade());
            change.setPlannedVolume(order.getPlannedVolume());
            change.setSourceMixerId(source.getId());
            change.setSourceMixerName(source.getName());
            change.setTargetMixerId(target.getId());
            change.setTargetMixerName(target.getName());
            change.setPlannedStart(order.getPlannedStart());
            change.setPlannedEnd(order.getPlannedEnd());
            changes.add(change);
        }
        changes.sort(Comparator.comparing(ReassignChangeVO::getPlannedStart,
                Comparator.nullsLast(Comparator.naturalOrder())));

        // 4.2 原主机置为故障
        faultMixer.setStatus("FAULT");
        mixerRepository.save(faultMixer);

        // 4.3 保存处置记录
        MixerFaultRecord record = new MixerFaultRecord();
        record.setRecordNo(nextRecordNo());
        record.setMixer(faultMixer);
        record.setPreviousStatus(previousStatus);
        record.setFaultReason(faultReason);
        record.setOccurredAt(occurredAt);
        record.setHandledAt(LocalDateTime.now());
        record.setAffectedCount(dbOrders.size());
        record.setReassignedCount(changes.size());
        record.setBlockedCount(blockedVOs.size());
        record.setStatus("CONFIRMED");
        record.setRemark(request.getRemark());
        faultRecordRepository.save(record);

        // 看板缓存立即失效，让主机负载刷新为最新
        try {
            redis.delete(DASHBOARD_CACHE_KEY);
        } catch (Exception ignored) {
            // 缓存不可用不影响处置结果
        }

        // ---- 5. 组装逐项变化结果 ----
        FaultConfirmVO vo = new FaultConfirmVO();
        vo.setRecordId(record.getId());
        vo.setRecordNo(record.getRecordNo());
        vo.setMixerId(faultMixer.getId());
        vo.setMixerCode(faultMixer.getMixerCode());
        vo.setMixerName(faultMixer.getName());
        vo.setStatusBefore(previousStatus);
        vo.setStatusBeforeName(Dict.mixerStatusName(previousStatus));
        vo.setStatusAfter(faultMixer.getStatus());
        vo.setStatusAfterName(Dict.mixerStatusName(faultMixer.getStatus()));
        vo.setFaultReason(faultReason);
        vo.setOccurredAt(occurredAt);
        vo.setHandledAt(record.getHandledAt());
        vo.setChanges(changes);
        vo.setBlocked(blockedVOs);
        vo.setAffectedTotal(dbOrders.size());
        vo.setReassignedCount(changes.size());
        vo.setBlockedCount(blockedVOs.size());
        return vo;
    }

    // ========================= 内部方法 =========================

    private void validateBase(FaultRequest request) {
        if (request == null) {
            throw new BizException("请求体不能为空");
        }
        if (request.getMixerId() == null) {
            throw new BizException("必须选择故障主机");
        }
        if (request.getFaultReason() == null || request.getFaultReason().isBlank()) {
            throw new BizException("故障原因不能为空");
        }
        if (request.getFaultReason().trim().length() > 200) {
            throw new BizException("故障原因不能超过 200 字");
        }
    }

    /** 只能对运行中或空闲的主机发起故障停机 */
    private Mixer requireFaultableMixer(Long mixerId) {
        Mixer mixer = mixerRepository.findById(mixerId)
                .orElseThrow(() -> new BizException("搅拌主机不存在：id=" + mixerId));
        if (!"RUNNING".equals(mixer.getStatus()) && !"IDLE".equals(mixer.getStatus())) {
            throw new BizException("主机 " + mixer.getName() + " 当前状态为「"
                    + Dict.mixerStatusName(mixer.getStatus())
                    + "」，只能对运行中或空闲的主机登记故障停机");
        }
        return mixer;
    }

    private void validateOccurredAt(LocalDateTime occurredAt) {
        if (occurredAt == null) {
            throw new BizException("故障发生时间不能为空");
        }
        if (occurredAt.isAfter(LocalDateTime.now().plusSeconds(FUTURE_TOLERANCE_SECONDS))) {
            throw new BizException("故障发生时间不能晚于当前时间");
        }
    }

    /** 可改派判定：尚未开工（PENDING）且没有任何发车记录；其余一律不可改派并给出原因 */
    private String classifyBlocked(ProductionOrder order, long ticketCount) {
        if ("DONE".equals(order.getStatus())) {
            return "DONE";
        }
        if ("PRODUCING".equals(order.getStatus())) {
            return "PRODUCING";
        }
        if (ticketCount > 0) {
            return "HAS_TICKET";
        }
        if (!"PENDING".equals(order.getStatus())) {
            return "UNKNOWN_STATUS";
        }
        return null;
    }

    private BlockedOrderVO toBlockedVO(ProductionOrder order, String reason, long ticketCount) {
        BlockedOrderVO vo = new BlockedOrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setProjectName(order.getProjectName());
        vo.setPourPart(order.getPourPart());
        vo.setStrengthGrade(order.getStrengthGrade());
        vo.setPlannedVolume(order.getPlannedVolume());
        vo.setStatus(order.getStatus());
        vo.setStatusName(Dict.orderStatusName(order.getStatus()));
        vo.setPlannedStart(order.getPlannedStart());
        vo.setPlannedEnd(order.getPlannedEnd());
        vo.setReason(reason);
        vo.setReasonText(blockedReasonText(reason, ticketCount));
        vo.setTicketCount(ticketCount);
        return vo;
    }

    private String blockedReasonText(String reason, long ticketCount) {
        switch (reason) {
            case "DONE":
                return "任务已完成，主机归属不再变更";
            case "PRODUCING":
                return ticketCount > 0
                        ? "任务正在生产中且已有 " + ticketCount + " 条发车记录，不能中断改派"
                        : "任务正在生产中，不能中断改派";
            case "HAS_TICKET":
                return "已有 " + ticketCount + " 条发车记录，混凝土已投入运输，不能改派";
            default:
                return "当前任务阶段不允许改派";
        }
    }

    private ReassignableOrderVO toReassignableVO(ProductionOrder order, Mixer faultMixer, List<Mixer> candidates) {
        ReassignableOrderVO vo = new ReassignableOrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setProjectName(order.getProjectName());
        vo.setPourPart(order.getPourPart());
        vo.setStrengthGrade(order.getStrengthGrade());
        vo.setPlannedVolume(order.getPlannedVolume());
        vo.setPlannedStart(order.getPlannedStart());
        vo.setPlannedEnd(order.getPlannedEnd());
        vo.setSourceMixerId(faultMixer.getId());
        vo.setSourceMixerName(faultMixer.getName());

        List<ProductionOrder> overlapsBuffer = new ArrayList<>();
        for (Mixer candidate : candidates) {
            CandidateMixerVO cv = new CandidateMixerVO();
            cv.setMixerId(candidate.getId());
            cv.setMixerCode(candidate.getMixerCode());
            cv.setMixerName(candidate.getName());
            cv.setModel(candidate.getModel());
            cv.setStatus(candidate.getStatus());
            cv.setStatusName(Dict.mixerStatusName(candidate.getStatus()));
            cv.setPlantName(candidate.getPlant() == null ? null : candidate.getPlant().getName());

            overlapsBuffer.clear();
            overlapsBuffer.addAll(orderRepository.findOverlapping(
                    candidate.getId(), order.getId(), order.getPlannedStart(), order.getPlannedEnd()));
            cv.setFreeInSlot(overlapsBuffer.isEmpty());
            if (!overlapsBuffer.isEmpty()) {
                cv.setConflictOrder(toConflictVO(overlapsBuffer.get(0)));
            }
            vo.getCandidates().add(cv);
        }
        return vo;
    }

    private ConflictOrderVO toConflictVO(ProductionOrder order) {
        ConflictOrderVO vo = new ConflictOrderVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setProjectName(order.getProjectName());
        vo.setStatus(order.getStatus());
        vo.setStatusName(Dict.orderStatusName(order.getStatus()));
        vo.setPlannedStart(order.getPlannedStart());
        vo.setPlannedEnd(order.getPlannedEnd());
        return vo;
    }

    /** 候选接替主机：非故障、非检修，且排除故障主机本身 */
    private List<Mixer> eligibleReplacementMixers(Long faultMixerId) {
        List<Mixer> result = new ArrayList<>();
        for (Mixer m : mixerRepository.findAllOrdered()) {
            if (m.getId().equals(faultMixerId)) {
                continue;
            }
            if ("RUNNING".equals(m.getStatus()) || "IDLE".equals(m.getStatus())) {
                result.add(m);
            }
        }
        return result;
    }

    /** 半开区间时段重叠判定：a.start < b.end && a.end > b.start */
    private boolean overlaps(ProductionOrder a, ProductionOrder b) {
        if (a.getPlannedStart() == null || a.getPlannedEnd() == null
                || b.getPlannedStart() == null || b.getPlannedEnd() == null) {
            return false;
        }
        return a.getPlannedStart().isBefore(b.getPlannedEnd()) && a.getPlannedEnd().isAfter(b.getPlannedStart());
    }

    private String slotText(ProductionOrder o) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        String start = o.getPlannedStart() == null ? "?" : o.getPlannedStart().format(fmt);
        String end = o.getPlannedEnd() == null ? "?" : o.getPlannedEnd().format(fmt);
        return start + " ~ " + end;
    }

    private String nextRecordNo() {
        LocalDate today = LocalDate.now();
        long seq = faultRecordRepository.maxId() + 1;
        return "GZ" + today.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-" + String.format("%03d", seq);
    }
}
