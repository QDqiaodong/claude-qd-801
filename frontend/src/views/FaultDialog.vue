<template>
  <el-dialog
    :model-value="modelValue"
    width="860px"
    top="6vh"
    :close-on-click-modal="false"
    append-to-body
    class="fault-dialog"
    @update:model-value="(v) => emit('update:modelValue', v)"
    @closed="reset"
  >
    <template #header>
      <div class="fd-header">
        <span class="fd-title">临时故障停机处置</span>
        <span v-if="mixer && mixer.mixerName" class="fd-sub">{{ mixer.mixerName }} · {{ mixer.mixerCode }}</span>
      </div>
    </template>

    <!-- 步骤指示 -->
    <el-steps :active="stepIndex" align-center finish-status="success" class="fd-steps">
      <el-step title="登记故障" description="填写原因与发生时间" />
      <el-step title="改派预演" description="核对受影响任务与接替主机" />
      <el-step title="确认结果" description="置故障 · 存记录 · 整批改派" />
    </el-steps>

    <!-- 第 1 步：登记故障 -->
    <div v-show="step === 'form'" class="fd-body">
      <el-alert
        v-if="mixer && mixer.status !== 'RUNNING' && mixer.status !== 'IDLE'"
        type="error"
        :closable="false"
        show-icon
        :title="`该主机当前为「${mixer.statusName}」，只能对运行中或空闲主机登记故障停机`"
        class="fd-alert"
      />
      <el-form label-width="96px" size="default">
        <el-form-item label="故障主机">
          <span class="cp-muted">{{ mixer?.mixerName }}（{{ mixer?.mixerCode }}） · {{ mixer?.plantName }}</span>
        </el-form-item>
        <el-form-item label="故障原因" required>
          <el-input
            v-model="form.faultReason"
            maxlength="200"
            show-word-limit
            placeholder="如：搅拌轴减速箱异响，需停机检查"
          />
        </el-form-item>
        <el-form-item label="发生时间" required>
          <el-date-picker
            v-model="form.occurredAt"
            type="datetime"
            placeholder="选择故障发生时间"
            format="MM-DD HH:mm"
            :disabled-date="disabledFutureDate"
            style="width: 260px"
          />
          <span class="cp-muted fd-hint">不可晚于当前时间</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="255" placeholder="可选，如已通知维修班 / 预计修复时间" />
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="预演为只读操作：仅列出受影响任务与改派方案，确认前不会改动任何数据。"
        class="fd-alert"
      />
    </div>

    <!-- 第 2 步：预演核对 -->
    <div v-show="step === 'preview' && preview" class="fd-body">
      <div class="fd-summary">
        <div class="fd-sum-item">
          <b>{{ preview.affectedTotal }}</b><span>受影响任务</span>
        </div>
        <div class="fd-sum-item ok">
          <b>{{ preview.reassignable.length }}</b><span>可改派</span>
        </div>
        <div class="fd-sum-item danger">
          <b>{{ preview.blocked.length }}</b><span>不可改派</span>
        </div>
        <div class="fd-sum-tip">
          每张可改派任务必须选择一台<b>非故障、非检修</b>的接替主机，<b>沿用原计划时段</b>；
          接替主机该时段已有任务时会标出冲突且不能选择。
        </div>
      </div>

      <!-- 可改派任务 -->
      <div class="fd-section-title">
        可改派任务（{{ preview.reassignable.length }}）
        <span class="cp-muted">— 尚未开工且无发车记录</span>
      </div>
      <el-empty v-if="!preview.reassignable.length" description="没有需要改派的待排任务" :image-size="56" />
      <div v-for="o in preview.reassignable" :key="o.orderId" class="fd-order">
        <div class="fd-order-head">
          <div class="fd-order-no">
            <b>{{ o.orderNo }}</b>
            <span class="cp-badge" :style="{ background: gradeColor(o.strengthGrade) }">{{ o.strengthGrade }}</span>
          </div>
          <div class="cp-muted">{{ o.projectName }} · {{ o.pourPart || '未指定部位' }}</div>
          <div class="cp-muted fd-slot">
            原主机：{{ o.sourceMixerName }} ｜ 原计划时段：{{ slotText(o.plannedStart, o.plannedEnd) }} ｜
            {{ o.plannedVolume }} m³
          </div>
        </div>
        <div class="fd-order-pick">
          <span class="fd-pick-label">接替主机</span>
          <el-select
            v-model="picks[o.orderId]"
            placeholder="请选择非故障、非检修的接替主机"
            style="width: 340px"
            @change="markDirty"
          >
            <el-option
              v-for="c in o.candidates"
              :key="c.mixerId"
              :value="c.mixerId"
              :disabled="!c.freeInSlot"
              :label="`${c.mixerName}（${c.mixerCode}）${c.freeInSlot ? '' : ' · 时段冲突'}`"
            >
              <span>{{ c.mixerName }}（{{ c.mixerCode }}）</span>
              <span v-if="c.freeInSlot" class="cp-muted" style="float: right">{{ c.statusName }}</span>
              <span v-else class="fd-conflict-text" style="float: right">
                冲突 {{ c.conflictOrder?.orderNo }}
              </span>
            </el-option>
          </el-select>
          <span v-if="!hasFreeCandidate(o)" class="cp-tag danger fd-noway">原时段无空闲主机，不能静默改时间</span>
        </div>
      </div>

      <!-- 不可改派任务 -->
      <div class="fd-section-title blocked">
        不可改派任务（{{ preview.blocked.length }}）
        <span class="cp-muted">— 保持在原主机，不随待排任务迁走</span>
      </div>
      <el-empty v-if="!preview.blocked.length" description="无" :image-size="40" />
      <el-table v-else :data="preview.blocked" size="small" border class="fd-table">
        <el-table-column prop="orderNo" label="任务单号" width="100" />
        <el-table-column label="工程 / 部位" min-width="180">
          <template #default="{ row }">
            {{ row.projectName }}<span class="cp-muted"> · {{ row.pourPart || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="任务阶段" width="90">
          <template #default="{ row }">
            <span class="cp-tag" :class="orderClass(row.status)">{{ row.statusName }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ticketCount" label="发车记录" width="80" align="center" />
        <el-table-column prop="reasonText" label="不可改派说明" min-width="200" />
      </el-table>

      <el-alert
        v-if="needsRecheck"
        type="error"
        :closable="false"
        show-icon
        title="确认被拒绝，所有数据保持确认前状态。任务阶段、发车记录或接替主机排期可能已变化，请重新预演核对。"
        class="fd-alert"
      />
    </div>

    <!-- 第 3 步：确认结果 -->
    <div v-show="step === 'result' && result" class="fd-body">
      <el-result icon="success" title="故障停机已确认，整批改派在同一业务操作中完成" :sub-title="`处置单号 ${result.recordNo}`">
            <template #extra>
              <div class="fd-result-box">
                <div class="fd-result-mixer">
                  <span>{{ result.mixerName }} 状态：</span>
                  <span class="cp-tag run">{{ result.statusBeforeName }}</span>
                  <span class="fd-arrow">→</span>
                  <span class="cp-tag danger">{{ result.statusAfterName }}</span>
                </div>
                <div class="cp-muted">
                  故障原因：{{ result.faultReason }} ｜ 发生：{{ dt(result.occurredAt) }} ｜ 处置：{{ dt(result.handledAt) }}
                </div>

                <div class="fd-section-title">逐项改派结果（{{ result.changes.length }}）— 计划时段保持不变</div>
                <el-empty v-if="!result.changes.length" description="本次无改派任务" :image-size="44" />
                <el-table v-else :data="result.changes" size="small" border class="fd-table">
                  <el-table-column prop="orderNo" label="任务单号" width="96" />
                  <el-table-column prop="projectName" label="工程" min-width="150" />
                  <el-table-column label="主机归属" min-width="150">
                    <template #default="{ row }">
                      {{ row.sourceMixerName }} <span class="fd-arrow">→</span> <b>{{ row.targetMixerName }}</b>
                    </template>
                  </el-table-column>
                  <el-table-column label="计划时段（沿用）" min-width="180">
                    <template #default="{ row }">{{ slotText(row.plannedStart, row.plannedEnd) }}</template>
                  </el-table-column>
                </el-table>

                <div v-if="result.blocked.length" class="fd-section-title blocked">
                  未改派任务（{{ result.blocked.length }}）— 已在处置记录中注明
                </div>
                <el-table v-if="result.blocked.length" :data="result.blocked" size="small" border class="fd-table">
                  <el-table-column prop="orderNo" label="任务单号" width="100" />
                  <el-table-column label="阶段" width="90">
                    <template #default="{ row }">
                      <span class="cp-tag" :class="orderClass(row.status)">{{ row.statusName }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="reasonText" label="说明" min-width="200" />
                </el-table>
              </div>
            </template>
      </el-result>
    </div>

    <template #footer>
      <div class="fd-footer">
        <el-button v-if="step === 'form'" @click="close">取消</el-button>
        <el-button v-if="step === 'form'" type="primary" :loading="loading" @click="doPreview">
          生成改派预演
        </el-button>

        <el-button v-if="step === 'preview'" :disabled="loading" @click="backToForm">返回修改</el-button>
        <el-button v-if="step === 'preview'" :loading="loading" @click="doPreview">重新预演</el-button>
        <el-popconfirm
          v-if="step === 'preview'"
          :title="confirmTitle"
          width="300"
          placement="top"
          @confirm="doConfirm"
        >
          <template #reference>
            <el-button type="danger" :loading="loading" :disabled="!allPicked || needsRecheck">
              确认故障停机并整批改派
            </el-button>
          </template>
        </el-popconfirm>

        <el-button v-if="step === 'result'" type="primary" @click="finishAndReload">完成，刷新看板</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fmtDateTime } from '../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  mixer: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'confirmed'])

const step = ref('form')
const loading = ref(false)
const preview = ref(null)
const result = ref(null)
const needsRecheck = ref(false)
const picks = reactive({})

const form = reactive({
  faultReason: '',
  occurredAt: new Date(),
  remark: ''
})

const stepIndex = computed(() => ({ form: 0, preview: 1, result: 2 }[step.value]))

const allPicked = computed(() => {
  if (!preview.value || !preview.value.reassignable.length) return true
  return preview.value.reassignable.every((o) => picks[o.orderId])
})

const confirmTitle = computed(() => {
  const n = preview.value ? preview.value.reassignable.length : 0
  return `确认后主机将置为故障，${n} 张任务在同一事务内整批改派；确认前会重新核对，不符则全部不执行。继续？`
})

function disabledFutureDate(d) {
  return d.getTime() > Date.now() + 24 * 3600 * 1000
}

function close() {
  emit('update:modelValue', false)
}

function reset() {
  step.value = 'form'
  preview.value = null
  result.value = null
  needsRecheck.value = false
  Object.keys(picks).forEach((k) => delete picks[k])
  form.faultReason = ''
  form.occurredAt = new Date()
  form.remark = ''
}

function backToForm() {
  step.value = 'form'
}

function markDirty() {
  needsRecheck.value = false
}

async function buildPayload() {
  if (!form.faultReason || !form.faultReason.trim()) {
    ElMessage.warning('请填写故障原因')
    return null
  }
  if (!form.occurredAt) {
    ElMessage.warning('请选择故障发生时间')
    return null
  }
  if (form.occurredAt.getTime() > Date.now()) {
    ElMessage.warning('故障发生时间不能晚于当前时间')
    return null
  }
  return {
    mixerId: props.mixer.mixerId,
    faultReason: form.faultReason.trim(),
    occurredAt: fmtDateTime(form.occurredAt),
    remark: form.remark || null
  }
}

async function doPreview() {
  const payload = await buildPayload()
  if (!payload) return
  loading.value = true
  try {
    const res = await api.faultPreview(payload)
    preview.value = res.data
    // 预演后默认清空旧选择，保证每张任务重新核对选择
    Object.keys(picks).forEach((k) => delete picks[k])
    for (const o of preview.value.reassignable) {
      picks[o.orderId] = null
      const onlyFree = o.candidates.filter((c) => c.freeInSlot)
      if (onlyFree.length === 1) {
        picks[o.orderId] = onlyFree[0].mixerId
      }
    }
    needsRecheck.value = false
    step.value = 'preview'
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function doConfirm() {
  if (!allPicked.value) {
    ElMessage.warning('每张可改派任务都必须选择接替主机')
    return
  }
  const payload = await buildPayload()
  if (!payload) return
  payload.assignments = preview.value.reassignable.map((o) => ({
    orderId: o.orderId,
    targetMixerId: picks[o.orderId]
  }))
  loading.value = true
  try {
    const res = await api.faultConfirm(payload)
    result.value = res.data
    step.value = 'result'
    needsRecheck.value = false
  } catch (e) {
    needsRecheck.value = true
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function finishAndReload() {
  emit('confirmed')
  close()
}

function hasFreeCandidate(o) {
  return o.candidates.some((c) => c.freeInSlot)
}

function dt(v) {
  if (!v) return '--'
  return String(v).replace('T', ' ').slice(0, 16)
}

function slotText(start, end) {
  return `${dt(start)} ~ ${end ? String(end).replace('T', ' ').slice(5, 16) : '--'}`
}

function gradeColor(grade) {
  return { C25: '#5b8fa8', C30: '#2f4f4f', C35: '#8a6f4f', C40: '#8c4a3a' }[grade] || '#2f4f4f'
}

function orderClass(status) {
  return { PENDING: 'warn', PRODUCING: 'run', DONE: 'ok' }[status] || ''
}
</script>

<style scoped>
.fd-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.fd-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--cp-primary);
}

.fd-sub {
  font-size: 12px;
  color: var(--cp-muted);
}

.fd-steps {
  padding: 6px 30px 14px;
}

.fd-body {
  max-height: 62vh;
  overflow-y: auto;
  padding: 0 4px;
}

.fd-alert {
  margin-top: 12px;
}

.fd-hint {
  margin-left: 10px;
  font-size: 11px;
}

.fd-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #f4f8f7;
  border: 1px solid var(--cp-border);
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 14px;
}

.fd-sum-item {
  text-align: center;
  min-width: 78px;
}

.fd-sum-item b {
  display: block;
  font-size: 22px;
  color: var(--cp-primary);
  line-height: 1.15;
}

.fd-sum-item.ok b {
  color: var(--cp-ok);
}

.fd-sum-item.danger b {
  color: var(--cp-danger);
}

.fd-sum-item span {
  font-size: 11px;
  color: var(--cp-muted);
}

.fd-sum-tip {
  flex: 1;
  font-size: 11px;
  color: var(--cp-muted);
  line-height: 1.7;
  padding-left: 14px;
  border-left: 1px solid var(--cp-border);
}

.fd-section-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--cp-primary);
  border-left: 3px solid var(--cp-primary);
  padding-left: 8px;
  margin: 16px 0 8px;
}

.fd-section-title.blocked {
  color: var(--cp-danger);
  border-left-color: var(--cp-danger);
}

.fd-order {
  border: 1px solid var(--cp-border);
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fff;
}

.fd-order-head {
  font-size: 12px;
  line-height: 1.8;
  margin-bottom: 6px;
}

.fd-order-no {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.fd-slot {
  font-size: 11px;
}

.fd-order-pick {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #fbfcfc;
  border-top: 1px dashed var(--cp-border);
  padding-top: 8px;
}

.fd-pick-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--cp-warn);
}

.fd-conflict-text {
  color: var(--cp-danger);
  font-size: 11px;
}

.fd-noway {
  font-size: 11px;
}

.fd-table {
  font-size: 12px;
}

.fd-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.fd-arrow {
  color: var(--cp-warn);
  margin: 0 2px;
}

.fd-result-box {
  text-align: left;
  width: 100%;
  font-size: 12px;
}

.fd-result-mixer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
}
</style>
