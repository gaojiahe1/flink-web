<script setup lang="ts">
import { computed, ref } from 'vue'
import { Square, Table2, Activity } from '@lucide/vue'
import { verificationTerminal, type SqlVerification } from '../api/verification'
import { materializeVerification } from '../utils/verificationResults'
const props = defineProps<{ verification: SqlVerification; busy: boolean }>()
const emit = defineEmits<{ stop: [] }>()
const tab = ref('data'),
  sink = ref(0)
const active = computed(() => !verificationTerminal(props.verification.state))
const tables = computed(() => materializeVerification(props.verification.rows))
const selected = computed(
  () => tables.value.find((table) => table.sink === sink.value) || tables.value[0],
)
const labels: Record<string, string> = {
  CREATED: '已创建',
  PREPARING: '准备中',
  SUBMITTING: '提交中',
  ACCEPTED: '已受理',
  RUNNING: '验证中',
  DRAINING: '正在收尾',
  CANCEL_PENDING: '等待停止确认',
  CANCELING: '正在停止',
  UNKNOWN: '状态待确认',
  COMPLETED: '已完成',
  FINISHED: '已结束',
  FAILED: '验证失败',
  CANCELED: '已停止',
  TIMED_OUT: '已达截止时间',
  ABORTED: '已中止',
}
</script>
<template>
  <section class="sql-verification-panel" aria-label="SQL 验证结果">
    <header class="verification-toolbar">
      <div class="verification-title">
        <Table2 :size="17" />
        <h3>验证结果</h3>
        <span class="badge" :class="{ running: active, failed: verification.state === 'FAILED' }">{{
          labels[verification.state] || verification.state
        }}</span>
      </div>
      <div class="verification-controls">
        <span class="muted"
          >已接收 {{ verification.rows.length }} / {{ verification.limit }} 条变更</span
        ><button v-if="active" class="button danger" :disabled="busy" @click="emit('stop')">
          <Square :size="14" />{{ busy ? '正在停止…' : '停止验证' }}</button
        ><span v-else class="muted">采集已结束</span>
      </div>
    </header>
    <div class="verification-navigation">
      <div role="tablist" aria-label="验证结果视图">
        <button
          class="text-button"
          role="tab"
          :aria-selected="tab === 'data'"
          @click="tab = 'data'"
        >
          <Table2 :size="14" />最终结果</button
        ><button
          class="text-button"
          role="tab"
          :aria-selected="tab === 'status'"
          @click="tab = 'status'"
        >
          <Activity :size="14" />运行状态
        </button>
      </div>
      <label v-if="tables.length > 1"
        >输出表<select v-model.number="sink" aria-label="验证输出表">
          <option v-for="table in tables" :key="table.sink" :value="table.sink">
            {{ table.target }}
          </option>
        </select></label
      ><span v-else-if="selected" class="muted"
        >{{ selected.target }} · {{ selected.rows.length }} 行</span
      >
    </div>
    <div v-if="tab === 'data'" role="tabpanel" aria-label="最终验证结果">
      <p v-if="selected?.warning" class="verification-warning" role="status">
        {{ selected.warning }}
      </p>
      <div v-if="selected?.columns.length" class="verification-grid-scroll">
        <table
          class="verification-final-table"
          :style="{ minWidth: selected.columns.length * 150 + 'px' }"
        >
          <thead>
            <tr>
              <th v-for="column in selected.columns" :key="column" scope="col">{{ column }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in selected.rows" :key="index">
              <td v-for="(cell, column) in row" :key="column">
                <span v-if="cell === null" class="null-cell">NULL</span
                ><span v-else>{{ cell }}</span>
              </td>
            </tr>
            <tr v-if="!selected.rows.length">
              <td :colspan="selected.columns.length" class="empty-state">
                当前结果为空，记录已被撤回或删除。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="verification-empty">
        {{ active ? '等待输出数据，可随时停止验证。' : '本次没有可展示的结构化结果。' }}
      </p>
      <p class="verification-footnote">
        仅展示输出字段；增删改事件已合并。结果限于本次采集范围，不代表全量业务结果。最多 1000
        条变更或两分钟后自动停止。
      </p>
    </div>
    <div v-else role="tabpanel" aria-label="验证运行状态" class="verification-status">
      <p>{{ verification.message }}</p>
      <small
        >SQL 修订 {{ verification.sqlRevision }} · Flink Job ID：{{
          verification.jobId || '待分配'
        }}</small
      >
    </div>
  </section>
</template>
<style scoped>
.sql-verification-panel {
  margin-top: 16px;
  border: 1px solid var(--line);
  border-radius: var(--panel-radius);
  background: var(--color-surface);
  min-width: 0;
}
.verification-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}
.verification-title,
.verification-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.verification-title > svg {
  color: var(--color-primary);
}
.verification-controls {
  font-size: 12px;
}
.verification-navigation {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 16px;
  gap: 10px;
  flex-wrap: wrap;
  border-bottom: 1px solid var(--line);
}
.verification-navigation > div {
  display: flex;
  gap: 18px;
}
.verification-navigation button {
  display: flex;
  gap: 6px;
  align-items: center;
  min-height: 30px;
}
.verification-navigation [aria-selected='true'] {
  color: var(--color-primary);
  font-weight: 600;
}
.verification-navigation > span,
.verification-navigation label {
  font-size: 12px;
}
.verification-navigation label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.verification-navigation select {
  width: auto;
  max-width: 260px;
}
.verification-grid-scroll {
  overflow: auto;
  max-height: 420px;
  min-height: 120px;
  scrollbar-gutter: stable;
}
.verification-final-table {
  border-collapse: separate;
  border-spacing: 0;
  width: 100%;
  table-layout: fixed;
}
.verification-final-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--color-background);
  font-size: 12px;
  text-align: left;
  font-weight: 600;
}
.verification-final-table th,
.verification-final-table td {
  padding: 11px 16px;
  border-bottom: 1px solid var(--line);
  vertical-align: top;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
.verification-final-table tr:last-child td {
  border-bottom: 0;
}
.verification-final-table tbody tr:hover {
  background: var(--color-background);
}
.null-cell {
  color: var(--color-muted);
  font-style: italic;
}
.verification-empty {
  padding: 32px 16px;
  text-align: center;
  color: var(--color-muted);
  font-size: 13px;
}
.verification-footnote,
.verification-warning {
  margin: 0;
  padding: 10px 16px;
  font-size: 12px;
  color: var(--color-muted);
  border-top: 1px solid var(--line);
}
.verification-warning {
  color: var(--color-warning);
  background: #fff8e9;
}
.verification-status {
  padding: 16px;
  min-height: 140px;
  overflow-wrap: anywhere;
}
.verification-status small {
  display: block;
  color: var(--color-muted);
  margin-top: 8px;
}
@media (max-width: 600px) {
  .verification-toolbar,
  .verification-navigation {
    padding: 12px;
  }
  .verification-controls {
    width: 100%;
    justify-content: space-between;
  }
  .verification-final-table th,
  .verification-final-table td {
    padding: 10px;
  }
}
</style>
