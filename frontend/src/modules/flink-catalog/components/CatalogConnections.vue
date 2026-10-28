<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'

const emit = defineEmits<{ close: []; changed: [] }>()
const dialog = ref<HTMLDialogElement>(),
  element = ref<HTMLFormElement>()
const rows = ref<Record<string, any>[]>([]),
  secrets = ref<Record<string, any>[]>([])
const editing = ref(false),
  busy = ref(false),
  error = ref(''),
  message = ref('')
const connections = computed(() =>
  rows.value.filter((r) => ['mysql', 'postgresql'].includes(r.connectorType)),
)
// 兼容既有连接存储契约；Catalog 不使用旧数据源的表/列占位字段，不创建远端表。
const initial = () => ({
  id: '',
  name: '',
  revision: 0,
  connectorType: 'mysql',
  host: '',
  port: '3306',
  database: '',
  schema: 'public',
  username: '',
  passwordRef: '',
  sslMode: 'PREFERRED',
  description: '',
  tableName: 'catalog_connection',
  table: 'catalog_connection',
  columns: [{ name: 'id', type: 'BIGINT', nullable: true, primaryKey: false }],
})
const form = reactive<Record<string, any>>(initial())
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  dialog.value?.close()
})
onMounted(() => {
  dialog.value?.showModal()
  void load()
})
async function load() {
  error.value = ''
  try {
    const [items, configs] = await Promise.all([
      request<Record<string, any>[]>('/api/catalog/datasources'),
      request<Record<string, any>[]>('/api/catalog/configs'),
    ])
    if (!disposed) {
      rows.value = items
      secrets.value = configs.filter((s) => ['secret', 'secretRef'].includes(s.kind))
    }
  } catch (e) {
    if (!disposed) error.value = (e as Error).message
  }
}
function edit(row?: Record<string, any>) {
  Object.keys(form).forEach((key) => delete form[key])
  Object.assign(form, initial(), row ? JSON.parse(JSON.stringify(row)) : {})
  editing.value = true
  error.value = ''
  message.value = ''
}
async function save() {
  if (busy.value || !element.value?.reportValidity()) return
  busy.value = true
  error.value = ''
  try {
    const saved = await request<Record<string, any>>(
      '/api/catalog/datasources/' + encodeURIComponent(form.id),
      { method: 'PUT', ...jsonBody(form) },
    )
    if (disposed) return
    rows.value = [...rows.value.filter((r) => r.id !== saved.id), saved]
    editing.value = false
    message.value = '连接已保存，可回到 Catalog 配置选择。'
    emit('changed')
  } catch (e) {
    if (!disposed) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function remove(row: Record<string, any>) {
  if (
    busy.value ||
    !(await confirmDialog('删除 JDBC 连接「' + row.name + '」？已被 Catalog 引用的连接不能删除。'))
  )
    return
  busy.value = true
  error.value = ''
  try {
    await request(
      '/api/catalog/datasources/' + encodeURIComponent(row.id) + '?revision=' + row.revision,
      { method: 'DELETE' },
    )
    if (disposed) return
    rows.value = rows.value.filter((r) => r.id !== row.id)
    emit('changed')
  } catch (e) {
    if (!disposed) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <dialog
    ref="dialog"
    class="catalog-connections"
    aria-label="Catalog JDBC 连接"
    @cancel.prevent="!busy && emit('close')"
  >
    <header class="field-heading">
      <div>
        <h3>Catalog JDBC 连接</h3>
        <p class="muted">仅供 JDBC Catalog 使用，密码通过配置引用。</p>
      </div>
      <button class="text-button" :disabled="busy" aria-label="关闭连接管理" @click="emit('close')">
        ×
      </button>
    </header>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <div class="toolbar-actions">
      <button class="button primary" :disabled="busy" @click="edit()">新增 JDBC 连接</button
      ><ManualRefreshButton :action="load" :disabled="busy" label="刷新连接" />
    </div>
    <form v-if="editing" ref="element" class="connection-form" @submit.prevent="save">
      <fieldset :disabled="busy" class="plain-fieldset field-grid">
        <label
          >连接标识<input
            v-model="form.id"
            required
            :disabled="!!form.revision"
            pattern="[a-z0-9][a-z0-9_.\-]{0,100}"
        /></label>
        <label>连接名称<input v-model="form.name" required maxlength="100" /></label>
        <label
          >数据库类型<select
            v-model="form.connectorType"
            @change="form.port = form.connectorType === 'postgresql' ? '5432' : '3306'"
          >
            <option value="mysql">MySQL</option>
            <option value="postgresql">PostgreSQL</option>
          </select></label
        >
        <label>主机<input v-model="form.host" required maxlength="253" /></label>
        <label>端口<input v-model="form.port" type="number" required min="1" max="65535" /></label>
        <label>数据库<input v-model="form.database" required maxlength="100" /></label>
        <label v-if="form.connectorType === 'postgresql'"
          >Schema<input v-model="form.schema" required
        /></label>
        <label>用户名<input v-model="form.username" required autocomplete="off" /></label>
        <label
          >密码配置引用<select v-model="form.passwordRef" required>
            <option value="">选择已登记密钥</option>
            <option v-for="secret in secrets" :key="secret.id" :value="secret.id">
              {{ secret.name || secret.id }}
            </option></select
          ><small>在“配置管理”中登记密码或 Secret 引用。</small></label
        >
        <label>说明<input v-model="form.description" maxlength="500" /></label>
      </fieldset>
      <p class="muted">
        Catalog 连接适配器当前使用 PREFERRED TLS 策略；已有其他策略保留，由 Catalog
        校验明确报告兼容性。
      </p>
      <div class="form-actions">
        <button class="button" type="button" :disabled="busy" @click="editing = false">
          取消编辑</button
        ><button class="button primary" :disabled="busy">保存连接</button>
      </div>
    </form>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>连接</th>
            <th>地址 / 数据库</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in connections" :key="row.id">
            <td>
              {{ row.name
              }}<small class="table-description">{{ row.id }} · {{ row.connectorType }}</small>
            </td>
            <td>
              {{ row.host }}:{{ row.port
              }}<small class="table-description">{{ row.database }}</small>
            </td>
            <td class="row-actions">
              <button class="text-button" :disabled="busy" @click="edit(row)">编辑</button
              ><button class="text-button danger" :disabled="busy" @click="remove(row)">
                删除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!connections.length" class="empty-state">暂无 JDBC 连接</p>
    </div>
    <footer class="form-actions">
      <button class="button" :disabled="busy" @click="emit('close')">返回 Catalog</button>
    </footer>
  </dialog>
</template>
<style scoped>
.catalog-connections {
  width: min(820px, calc(100vw - 32px));
  max-height: calc(100dvh - 32px);
  padding: 20px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  color: var(--text);
  background: var(--color-surface);
}
.catalog-connections::backdrop {
  background: var(--color-overlay);
}
.field-heading {
  margin-bottom: 16px;
}
.connection-form {
  margin-block: 16px;
  padding: 16px;
  background: var(--color-surface-soft);
  border: 1px solid var(--line);
  border-radius: 6px;
}
.connection-form > p {
  margin-top: 12px;
  font-size: 12px;
}
.table-wrap {
  margin-top: 16px;
}
table {
  min-width: 520px;
}
</style>
