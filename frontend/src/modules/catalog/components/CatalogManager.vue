<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import { computed, reactive, ref, watch } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
import { definitions, titles, type CatalogModule } from '../model/catalogDefinitions'
import {
  auditActionLabel,
  auditOutcomeLabel,
  auditTime,
  auditTimeLabel,
} from '../../audit/utils/auditLabels'
type Row = Record<string, any>
const props = defineProps<{ module: CatalogModule }>()
const type = computed(() => props.module)
const rows = ref<Row[]>([]),
  form = reactive<Row>({}),
  editing = ref(false),
  busy = ref(false),
  error = ref(''),
  message = ref('')
const showSecret = ref(false),
  query = ref(''),
  jsonValue = ref(false),
  page = ref(1)
const pageSize = 10
const admin = computed(() => currentUser.value?.roles.some((r) => r.endsWith('ADMIN')))
const api = computed(() =>
  type.value === 'users'
    ? '/api/users'
    : type.value === 'audit'
      ? '/api/audit'
      : '/api/catalog/' + type.value,
)
const fields = computed(() =>
  (definitions[type.value] || []).filter(
    (f) =>
      type.value !== 'configs' ||
      (form.kind === 'secretRef'
        ? !['value'].includes(f.key)
        : !['secretName', 'secretKey'].includes(f.key)),
  ),
)
const filtered = computed(() =>
  rows.value.filter((r) =>
    (
      JSON.stringify(r) +
      (type.value === 'audit' ? auditActionLabel(r.action) + auditOutcomeLabel(r.outcome) : '')
    )
      .toLowerCase()
      .includes(query.value.toLowerCase()),
  ),
)
const sorted = computed(() =>
  type.value === 'audit'
    ? [...filtered.value].sort(
        (a, b) => (auditTime(b)?.getTime() || 0) - (auditTime(a)?.getTime() || 0),
      )
    : filtered.value,
)
const pages = computed(() => Math.max(1, Math.ceil(sorted.value.length / pageSize)))
const visible = computed(() =>
  sorted.value.slice(
    (Math.min(page.value, pages.value) - 1) * pageSize,
    Math.min(page.value, pages.value) * pageSize,
  ),
)
watch([query, type], () => (page.value = 1))
async function load() {
  error.value = ''
  const path = api.value
  try {
    const result = await request<Row[]>(path)
    if (path === api.value) rows.value = result
  } catch (e) {
    if (path === api.value) error.value = showValidationError((e as Error).message)
  }
}
watch(
  type,
  () => {
    editing.value = false
    rows.value = []
    message.value = ''
    query.value = ''
    void load()
  },
  { immediate: true },
)
watch(
  form,
  () => {
    error.value = ''
    message.value = ''
  },
  { deep: true },
)
function edit(row?: Row) {
  for (const key of Object.keys(form)) delete form[key]
  Object.assign(
    form,
    row || {
      id: '',
      revision: 0,
      kind: 'plain',
      enabled: true,
      role: 'VIEWER',
      yaml: '',
      value: '',
    },
  )
  if (form.kind === 'secret') form.value = ''
  form.password = ''
  showSecret.value = false
  editing.value = true
  jsonValue.value = false
}
async function save() {
  busy.value = true
  error.value = ''
  try {
    const payload = { ...form }
    if (type.value === 'configs' && form.kind === 'plain' && jsonValue.value)
      payload.value = JSON.parse(form.value)
    const destination = api.value
    const saved = await request<Row>(destination + '/' + encodeURIComponent(form.id), {
      method: 'PUT',
      ...jsonBody(payload),
    })
    if (api.value !== destination) return
    // 保存响应已经由服务端脱敏，直接更新列表，避免成功后再次读取失败导致空列表。
    rows.value = [...rows.value.filter((row) => row.id !== saved.id), saved]
    editing.value = false
    message.value = '已保存'
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function remove(row: Row) {
  if (!(await confirmDialog('删除记录 ' + row.id + '？配置删除可能影响后续提交。'))) return
  try {
    await request(api.value + '/' + encodeURIComponent(row.id) + '?revision=' + row.revision, {
      method: 'DELETE',
    })
    await load()
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  }
}
function summary(row: Row): string {
  if (type.value === 'configs')
    return row.kind === 'secret'
      ? '••••••••（已加密）'
      : row.kind === 'secretRef'
        ? row.secretName + '/' + row.secretKey
        : typeof row.value === 'object'
          ? JSON.stringify(row.value)
          : String(row.value ?? '—')
  if (type.value === 'images') return row.repository + ':' + row.version
  if (type.value === 'users') return row.enabled === false ? '禁用' : '启用'
  return row.description || '—'
}
function resetFilters() {
  query.value = ''
}
</script>
<template>
  <PageHeader eyebrow="PLATFORM RESOURCES"
    ><template #title>{{ titles[type] }}</template
    ><template #description>{{
      type === 'audit'
        ? '记录平台写操作与提交结果，不记录明文密钥。'
        : '统一登记配置，供作业模板和表单引用。'
    }}</template
    ><template #actions
      ><button v-if="admin && type !== 'audit'" class="button primary" @click="edit()">
        ＋ 新增
      </button></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice">{{ message }}</div>
  <section v-if="editing" class="panel form-panel registry-editor" aria-label="记录编辑">
    <h3>{{ form.revision ? '编辑记录' : '新增记录' }}</h3>
    <form @submit.prevent="save">
      <div class="field-grid">
        <label
          >{{ type === 'users' ? '用户名' : '唯一标识'
          }}<input
            data-field="id"
            v-model="form.id"
            pattern="[a-z0-9][a-z0-9_.\-]{0,100}"
            required
            :disabled="!!form.revision"
          /><small>{{
            type === 'users'
              ? '使用字母、数字、点、下划线或连字符。'
              : '小写字母、数字、点、下划线、连字符；用于变量引用。'
          }}</small></label
        >
        <label
          v-for="field in fields"
          :key="field.key"
          :class="{ 'wide-field': field.type === 'textarea' }"
          >{{ field.label }}
          <select :data-field="field.key" v-if="field.choices" v-model="form[field.key]">
            <option v-for="choice in field.choices" :key="choice">{{ choice }}</option>
          </select>
          <CodeEditor
            :data-field="field.key"
            v-else-if="field.type === 'textarea'"
            v-model="form[field.key]"
            label="模板 YAML"
          />
          <input
            :data-field="field.key"
            v-else-if="field.type === 'checkbox'"
            v-model="form[field.key]"
            type="checkbox"
          />
          <input
            :data-field="field.key"
            v-else
            v-model="form[field.key]"
            :type="
              field.type === 'secret' && !showSecret && (type === 'users' || form.kind === 'secret')
                ? 'password'
                : 'text'
            "
            :required="['repository', 'version'].includes(field.key)"
            :pattern="
              field.key === 'version' && type === 'images'
                ? '(1\\.(16|17|18|19|20)|2\\.[0-3])(\\.[0-9]+)?([\\-_].*)?'
                : undefined
            "
            autocomplete="off"
          />
          <small>{{ field.help }}</small>
        </label>
      </div>
      <label
        v-if="type === 'users' || (type === 'configs' && form.kind === 'secret')"
        class="inline-label"
        ><input
          data-field="showSecret"
          v-model="showSecret"
          type="checkbox"
        />显示本次输入的密码/密钥</label
      ><label v-if="type === 'configs' && form.kind === 'plain'" class="inline-label"
        ><input data-field="jsonValue" v-model="jsonValue" type="checkbox" />将配置值按 JSON
        类型解析</label
      >
      <div class="form-actions">
        <button type="button" class="button" @click="editing = false">取消</button
        ><button class="button primary" :disabled="busy">保存</button>
      </div>
    </form>
  </section>
  <section class="panel registry-list">
    <div class="panel-toolbar">
      <h3>记录列表</h3>
      <div class="toolbar-actions">
        <input data-field="query" v-model="query" placeholder="搜索记录" /><button
          class="text-button"
          @click="resetFilters"
        >
          重置</button
        ><ManualRefreshButton :action="load" label="刷新列表" />
      </div>
    </div>
    <div v-if="!filtered.length" class="empty-state">暂无记录</div>
    <div v-else class="table-wrap catalog-records">
      <table v-if="type === 'audit'">
        <thead>
          <tr>
            <th>时间</th>
            <th>操作者</th>
            <th>动作</th>
            <th>目标</th>
            <th>结果</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visible" :key="row.id">
            <td class="audit-time">
              {{ auditTimeLabel(row)
              }}<small
                v-if="row.timeSource === 'createdAt' || (!row.time && row.createdAt)"
                class="muted table-description"
                >按记录创建时间</small
              >
            </td>
            <td>{{ row.actor }}</td>
            <td :title="row.action">{{ auditActionLabel(row.action) }}</td>
            <td :title="row.target">
              <strong>{{ row.targetName || row.target || '未记录目标' }}</strong
              ><small
                v-if="row.targetType || row.targetReference"
                class="muted table-description"
                >{{ [row.targetType, row.targetReference].filter(Boolean).join(' · ') }}</small
              >
            </td>
            <td :title="row.outcome">{{ auditOutcomeLabel(row.outcome) }}</td>
            <td>
              <details class="audit-details">
                <summary>查看详情</summary>
                <dl>
                  <dt>目标标识</dt>
                  <dd>{{ row.target || '未记录' }}</dd>
                  <dt>动作码</dt>
                  <dd>{{ row.action }}</dd>
                  <dt>原始时间</dt>
                  <dd>{{ row.time || row.createdAt || '未记录' }}</dd>
                  <template v-if="row.context?.pod"
                    ><dt>Pod / 容器</dt>
                    <dd>{{ row.context.pod }} / {{ row.context.container }}</dd>
                    <dt>日志范围</dt>
                    <dd>
                      {{ row.context.previous ? '上次容器' : '当前容器' }} · 最近
                      {{ row.context.lines }} 行
                    </dd></template
                  ><template v-if="row.outcome === 'ACCEPTED'"
                    ><dt>结果说明</dt>
                    <dd>请求已受理，实际运行结果请在作业或任务中查看。</dd></template
                  >
                </dl>
              </details>
            </td>
          </tr>
        </tbody>
      </table>
      <table v-else>
        <thead>
          <tr>
            <th>标识</th>
            <th>{{ type === 'users' ? '角色' : '名称' }}</th>
            <th>
              {{
                type === 'images'
                  ? '镜像地址'
                  : type === 'configs'
                    ? '值 / 引用'
                    : type === 'users'
                      ? '账户状态'
                      : '说明'
              }}
            </th>
            <th>记录版本</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visible" :key="row.id">
            <td class="mono">{{ row.id }}</td>
            <td :title="row.name || row.role">{{ row.name || row.role }}</td>
            <td :title="summary(row)">
              {{ summary(row) }}
              <small v-if="type === 'images' && row.flinkVersion" class="muted table-description"
                >Flink {{ row.flinkVersion }}</small
              >
            </td>
            <td>{{ row.revision }}</td>
            <td>
              <button class="text-button" @click="admin ? edit(row) : undefined" :disabled="!admin">
                编辑</button
              ><button v-if="admin" class="text-button danger" @click="remove(row)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="pagination">
      <span>共 {{ filtered.length }} 条</span>
      <div class="toolbar-actions">
        <button class="button" :disabled="page <= 1" @click="page--">上一页</button
        ><span>{{ Math.min(page, pages) }} / {{ pages }}</span
        ><button class="button" :disabled="page >= pages" @click="page++">下一页</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.audit-time {
  white-space: nowrap;
}
.audit-details {
  min-width: 88px;
  max-width: 320px;
}
.audit-details summary {
  cursor: pointer;
  color: var(--color-primary);
  white-space: nowrap;
}
.audit-details dl {
  display: grid;
  gap: 3px;
  margin: 8px 0;
  font-size: var(--font-small);
  overflow-wrap: anywhere;
}
.audit-details dt {
  color: var(--color-muted);
}
.audit-details dd {
  margin: 0 0 6px;
  white-space: normal;
}
</style>
