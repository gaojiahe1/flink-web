<script setup lang="ts">
import { showValidationError } from '../../../shared/utils/fieldValidation'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
import ManualRefreshButton from '../../../shared/components/form/ManualRefreshButton.vue'
import HelpTip from '../../../shared/components/form/HelpTip.vue'
import { sourceTypes, isJdbc, isKafka, configurationOnly } from '../model/sourceTypes'

import MetadataBrowser from '../components/MetadataBrowser.vue'
import CodeEditor from '../../../shared/components/editor/CodeEditor.vue'
import type { ColumnDefinition } from '../../flink-catalog/api/catalogs'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { request, jsonBody } from '../../../shared/api/http'
import { currentUser } from '../../permission/stores/auth'
type Source = {
  id: string
  name: string
  revision: number
  connectorType: string
  tableName: string
  description: string
  columns: ColumnDefinition[]
  [key: string]: any
}
const metadataSource = ref<Source>(),
  sqlPreview = ref(''),
  previewName = ref('')
const developer = computed(() =>
  currentUser.value?.roles.some((r) => ['ROLE_ADMIN', 'ROLE_DEVELOPER'].includes(r)),
)
const rows = ref<Source[]>([]),
  secrets = ref<{ id: string; name: string; kind: string }[]>([]),
  query = ref(''),
  type = ref(''),
  editing = ref(false),
  busy = ref(false),
  error = ref(''),
  message = ref('')
const initial = (): Source => ({
  id: '',
  name: '',
  revision: 0,
  connectorType: 'kafka',
  tableName: 'source_events',
  description: '',
  columns: [{ name: 'id', type: 'BIGINT', nullable: true, primaryKey: false }],
  brokers: '',
  topic: '',
  groupId: 'flink-web',
  format: 'json',
  startupMode: 'group-offsets',
  startupTimestamp: 0,
  watermarkColumn: '',
  watermarkDelaySeconds: 5,
  host: '',
  port: '3306',
  database: '',
  table: '',
  username: '',
  passwordRef: '',
  sslMode: 'PREFERRED',
  schema: 'public',
  path: 's3://bucket/data/',
  rowsPerSecond: 10,
})
const form = reactive<Source>(initial()),
  element = ref<HTMLFormElement>()
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const filtered = computed(() =>
  rows.value.filter(
    (r) =>
      (r.name + r.id + r.description).toLowerCase().includes(query.value.toLowerCase()) &&
      (!type.value || r.connectorType === type.value),
  ),
)
async function load() {
  try {
    ;[rows.value, secrets.value] = await Promise.all([
      request<Source[]>('/api/catalog/datasources'),
      request<any[]>('/api/catalog/configs'),
    ])
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  }
}
onMounted(load)
function edit(row?: Source) {
  Object.assign(form, initial(), row ? JSON.parse(JSON.stringify(row)) : {})
  form.columns = form.columns.map((c) => ({
    ...c,
    nullable: c.nullable ?? true,
    primaryKey: c.primaryKey ?? false,
  }))
  editing.value = true
  error.value = ''
}
async function save() {
  if (!element.value?.reportValidity()) return
  busy.value = true
  error.value = ''
  try {
    const saved = await request<Source>('/api/catalog/datasources/' + encodeURIComponent(form.id), {
      method: 'PUT',
      ...jsonBody(form),
    })
    rows.value = [...rows.value.filter((r) => r.id !== saved.id), saved]
    editing.value = false
    message.value = '数据源已保存，可在 SQL 开发页插入建表语句。'
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function probe(row: Source, schema = false) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await request<{ message: string; columns?: { name: string; type: string }[] }>(
      '/api/datasources/' + row.id + '/probe',
      { method: 'POST', ...jsonBody({ schema }) },
    )
    if (schema && result.columns) {
      if (
        editing.value &&
        form.id === row.id &&
        (await confirmDialog('用数据库字段替换当前表结构？需要保存后才会生效。'))
      )
        form.columns = result.columns
      else if (!editing.value) {
        edit(row)
        form.columns = result.columns
      }
    }
    message.value = result.message
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function remove(row: Source) {
  if (!(await confirmDialog('删除数据源 ' + row.name + '？已插入的 SQL 不会改变。'))) return
  try {
    await request('/api/catalog/datasources/' + row.id + '?revision=' + row.revision, {
      method: 'DELETE',
    })
    rows.value = rows.value.filter((r) => r.id !== row.id)
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  }
}

async function previewSql(row: Source) {
  busy.value = true
  error.value = ''
  try {
    const result = await request<{ sql: string }>('/api/sql/datasources/' + row.id + '/snippet')
    previewName.value = row.name
    sqlPreview.value = result.sql
  } catch (e) {
    error.value = showValidationError((e as Error).message)
  } finally {
    busy.value = false
  }
}
async function applyMetadata(value: {
  database: string
  table: string
  columns: ColumnDefinition[]
}) {
  if (!metadataSource.value || !(await confirmDialog('使用当前表结构编辑数据源？保存后才会生效。')))
    return
  edit(metadataSource.value)
  form.database = value.database
  if (form.connectorType === 'postgresql' && value.table.includes('.')) {
    const [schema, table] = value.table.split('.', 2)
    form.schema = schema
    form.table = table
  } else form.table = value.table
  form.columns = value.columns
  message.value = '已载入字段、非空和主键，请复核后保存。'
}
function typeChanged() {
  form.port = form.connectorType === 'postgresql' ? '5432' : '3306'
  form.columns.forEach((c) => (c.primaryKey = false))
  form.format = 'json'
}
function target(row: Source) {
  if (isKafka(row.connectorType)) return row.brokers + ' / ' + row.topic
  if (isJdbc(row.connectorType)) return row.host + ':' + row.port + ' / ' + row.database
  return row.path || (row.connectorType === 'print' ? 'TaskManager 日志' : '内存生成')
}
function resetFilters() {
  query.value = ''
  type.value = ''
}
</script>
<template>
  <PageHeader eyebrow="DATA CONNECTIONS"
    ><template #title>数据源管理</template
    ><template #description>维护连接、表结构和验证结果，在 SQL 中复用。</template
    ><template #actions
      ><button v-if="admin" class="button primary" @click="edit()">＋ 注册数据源</button></template
    ></PageHeader
  >
  <div v-if="error" class="notice error" role="alert">{{ error }}</div>
  <div v-if="message" class="notice" role="status">{{ message }}</div>
  <section v-if="editing" class="panel form-panel compact-form detail-panel">
    <div class="field-heading">
      <h3>{{ form.revision ? '编辑数据源' : '注册数据源' }}</h3>
      <button class="text-button" @click="editing = false">收起</button>
    </div>
    <form ref="element" @submit.prevent="save">
      <fieldset :disabled="busy" class="plain-fieldset">
        <div class="field-grid">
          <label
            >唯一标识<input
              data-field="id"
              v-model="form.id"
              required
              pattern="[a-z0-9][a-z0-9_.\-]{0,100}"
              :disabled="!!form.revision" /></label
          ><label
            >数据源名称<input data-field="name" v-model="form.name" required maxlength="100"
          /></label>
          <label
            >连接类型<select
              data-field="connectorType"
              v-model="form.connectorType"
              aria-label="连接类型"
              @change="typeChanged"
            >
              <option v-for="kind in sourceTypes" :key="kind.value" :value="kind.value">
                {{ kind.label }}
              </option>
            </select></label
          ><label
            >SQL 表名<input
              data-field="tableName"
              v-model="form.tableName"
              required
              pattern="[A-Za-z_][A-Za-z0-9_]{0,63}"
          /></label>
          <template v-if="isKafka(form.connectorType)"
            ><label
              >Bootstrap servers<input
                data-field="brokers"
                v-model="form.brokers"
                required
                placeholder="broker:9092" /><HelpTip
                text="多个地址使用逗号分隔；基础版本使用集群内明文协议。" /></label
            ><label>Topic<input data-field="topic" v-model="form.topic" required /></label
            ><label v-if="form.connectorType === 'kafka'"
              >消费组<input data-field="groupId" v-model="form.groupId" required /></label
            ><label
              >消息格式<select data-field="format" v-model="form.format">
                <option>json</option>
                <option>csv</option>
                <option v-if="form.connectorType === 'kafka'">raw</option>
              </select></label
            >
            <label v-if="form.connectorType === 'kafka'"
              >起始位置<select
                data-field="startupMode"
                v-model="form.startupMode"
                aria-label="起始位置"
              >
                <option value="group-offsets">消费组已提交位点</option>
                <option value="earliest-offset">最早位点</option>
                <option value="latest-offset">最新位点</option>
                <option value="timestamp">指定时间戳</option>
              </select></label
            >
            <label v-if="form.startupMode === 'timestamp'"
              >起始时间戳（毫秒）<input
                data-field="startupTimestamp"
                v-model.number="form.startupTimestamp"
                type="number"
                min="0"
                step="1"
                required /></label
          ></template>
          <template v-else-if="isJdbc(form.connectorType)"
            ><label
              >主机<input
                data-field="host"
                v-model="form.host"
                required
                placeholder="mysql.default.svc" /></label
            ><label
              >端口<input
                data-field="port"
                v-model="form.port"
                required
                type="number"
                min="1"
                max="65535" /></label
            ><label>数据库<input data-field="database" v-model="form.database" required /></label
            ><label v-if="form.connectorType === 'postgresql'"
              >Schema<input
                data-field="schema"
                v-model="form.schema"
                required
                pattern="[A-Za-z_][A-Za-z0-9_]{0,63}" /></label
            ><label>数据库表<input data-field="table" v-model="form.table" required /></label
            ><label
              >TLS 模式<select data-field="sslMode" v-model="form.sslMode">
                <option>PREFERRED</option>
                <option>REQUIRED</option>
                <option>VERIFY_CA</option>
                <option>VERIFY_IDENTITY</option>
                <option>DISABLED</option>
              </select></label
            ><label
              >用户名<input
                data-field="username"
                v-model="form.username"
                required
                autocomplete="off" /></label
            ><label
              >密码配置引用<select data-field="passwordRef" v-model="form.passwordRef" required>
                <option value="">选择已注册的密钥</option>
                <option
                  v-for="secret in secrets.filter((s) => ['secret', 'secretRef'].includes(s.kind))"
                  :key="secret.id"
                  :value="secret.id"
                >
                  {{ secret.name }} · {{ secret.id }}
                </option></select
              ><HelpTip text="先在配置管理中注册密码，SQL 和页面均不保存明文密码。" /></label
          ></template>
          <template v-else-if="form.connectorType === 'filesystem'">
            <label
              >文件路径<input
                data-field="path"
                v-model="form.path"
                required
                placeholder="s3://bucket/path/" /><HelpTip
                text="S3/S3A/HDFS/file 路径由 Flink 容器访问，凭据通过运行时配置或 Pod 身份提供；此处只校验路径。"
            /></label>
            <label
              >文件格式<select data-field="format" v-model="form.format">
                <option>json</option>
                <option>csv</option>
                <option>parquet</option>
                <option>orc</option>
                <option>raw</option>
              </select></label
            >
          </template>
          <label v-else-if="form.connectorType === 'datagen'"
            >每秒生成行数<input
              data-field="rowsPerSecond"
              v-model.number="form.rowsPerSecond"
              type="number"
              min="1"
              max="100000"
              step="1"
              required
          /></label>
          <label class="wide-field"
            >描述<input data-field="description" v-model="form.description" maxlength="500"
          /></label>
        </div>
        <div class="field-heading">
          <h3>表结构</h3>
          <button
            v-if="admin && form.revision && isJdbc(form.connectorType)"
            type="button"
            class="button"
            :disabled="busy"
            @click="
              probe(
                rows.find((r) => r.id === form.id)!,
                true,
              )
            "
          >
            加载已保存连接的字段
          </button>
        </div>
        <HelpTip
          text="按实际数据定义字段；JDBC 为有界读取，Upsert Kafka 要求主键。连接器、格式插件和驱动需由运行镜像提供。"
        />
        <div v-for="(column, index) in form.columns" :key="index" class="column-row">
          <input
            data-field="name"
            v-model="column.name"
            :aria-label="'字段名称 ' + (index + 1)"
            required
            pattern="[A-Za-z_][A-Za-z0-9_]{0,63}"
          /><input
            data-field="type"
            v-model="column.type"
            list="flink-data-types"
            :aria-label="'字段类型 ' + (index + 1)"
            required
            placeholder="DECIMAL(20,4)"
          />
          <label class="column-flag"
            ><input
              data-field="nullable"
              v-model="column.nullable"
              type="checkbox"
              :disabled="column.primaryKey"
              :aria-label="'允许空值 ' + (index + 1)"
            />可空</label
          >
          <label class="column-flag"
            ><input
              data-field="primaryKey"
              v-model="column.primaryKey"
              type="checkbox"
              :disabled="!isJdbc(form.connectorType) && form.connectorType !== 'upsert-kafka'"
              :aria-label="'主键 ' + (index + 1)"
            />主键</label
          ><button
            type="button"
            class="text-button danger"
            :disabled="form.columns.length === 1"
            @click="form.columns.splice(index, 1)"
          >
            移除
          </button>
        </div>
        <button
          type="button"
          class="button"
          :disabled="form.columns.length >= 100"
          @click="
            form.columns.push({ name: '', type: 'STRING', nullable: true, primaryKey: false })
          "
        >
          ＋ 添加字段
        </button>
        <datalist id="flink-data-types">
          <option
            v-for="t in [
              'STRING',
              'BYTES',
              'INT',
              'BIGINT',
              'BOOLEAN',
              'DOUBLE',
              'FLOAT',
              'DATE',
              'TIMESTAMP(3)',
              'TIMESTAMP_LTZ(3)',
              'DECIMAL(18,2)',
            ]"
            :key="t"
            :value="t"
          />
        </datalist>
        <div class="field-grid watermark-fields">
          <label
            >事件时间 Watermark<select data-field="watermarkColumn" v-model="form.watermarkColumn">
              <option value="">不配置 Watermark</option>
              <option
                v-for="column in form.columns.filter((c) =>
                  ['TIMESTAMP(3)', 'TIMESTAMP_LTZ(3)'].includes(c.type.toUpperCase()),
                )"
                :key="column.name"
                :value="column.name"
              >
                {{ column.name }}
              </option></select
            ><HelpTip text="选择时间字段后，生成延迟容忍的 Watermark 定义。"
          /></label>
          <label
            >Watermark 延迟（秒）<input
              data-field="watermarkDelaySeconds"
              v-model.number="form.watermarkDelaySeconds"
              type="number"
              min="0"
              max="86400"
              step="1"
              :disabled="!form.watermarkColumn"
          /></label>
        </div>
      </fieldset>
      <div class="form-actions">
        <button type="button" class="button" @click="editing = false">取消</button
        ><button class="button primary" :disabled="busy">保存数据源</button>
      </div>
    </form>
  </section>
  <section class="panel detail-panel">
    <div class="filter-bar">
      <input
        data-field="query"
        v-model="query"
        class="search"
        placeholder="搜索名称 / 标识 / 描述"
      /><select data-field="type" v-model="type" aria-label="数据源类型筛选">
        <option value="">全部类型</option>
        <option v-for="kind in sourceTypes" :key="kind.value" :value="kind.value">
          {{ kind.label }}
        </option></select
      ><button class="text-button" @click="resetFilters">重置</button
      ><ManualRefreshButton :action="load" />
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>SQL 表名</th>
            <th>连接目标</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in filtered" :key="row.id">
            <td>
              <strong>{{ row.name }}</strong
              ><small class="table-description">{{ row.id }}</small>
            </td>
            <td>
              <span class="badge neutral">{{ row.connectorType.toUpperCase() }}</span>
            </td>
            <td class="mono">{{ row.tableName }}</td>
            <td>
              {{ target(row) }}
            </td>
            <td>
              <button class="text-button" :disabled="busy" @click="previewSql(row)">建表 SQL</button
              ><button
                v-if="developer && isJdbc(row.connectorType)"
                class="text-button"
                :disabled="busy"
                @click="metadataSource = row"
              >
                库表浏览</button
              ><button v-if="admin" class="text-button" @click="edit(row)">编辑</button
              ><button v-if="admin" class="text-button" :disabled="busy" @click="probe(row)">
                {{ configurationOnly(row.connectorType) ? '校验配置' : '测试连接' }}</button
              ><button
                v-if="admin && isJdbc(row.connectorType)"
                class="text-button"
                :disabled="busy"
                @click="probe(row, true)"
              >
                读取字段</button
              ><RouterLink class="text-button" to="/sql">SQL 开发</RouterLink
              ><button v-if="admin" class="text-button danger" @click="remove(row)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!filtered.length" class="empty-state">
      <h3>暂无匹配的数据源</h3>
      <p>注册数据源后，即可在 SQL 工作区生成建表语句。</p>
    </div>
    <div class="panel-footer">
      共 {{ filtered.length }} 个数据源 · 登记不连接数据源；连接测试和字段读取仅由用户触发
    </div>
  </section>
  <section v-if="sqlPreview" class="panel form-panel detail-panel catalog-detail">
    <div class="field-heading">
      <h3>{{ previewName }} · 建表 SQL</h3>
      <button class="text-button" @click="sqlPreview = ''">收起</button>
    </div>
    <CodeEditor :model-value="sqlPreview" language="sql" readonly label="数据源建表 SQL" />
  </section>
  <section v-if="metadataSource && developer" class="panel form-panel detail-panel">
    <div class="field-heading">
      <h3>{{ metadataSource.name }} · 元数据</h3>
      <button class="text-button" @click="metadataSource = undefined">收起</button>
    </div>
    <MetadataBrowser
      :key="metadataSource.id"
      :endpoint="'/api/sql/datasources/' + metadataSource.id + '/metadata'"
      :action-label="admin ? '将字段载入数据源编辑' : undefined"
      @select="applyMetadata"
    />
  </section>
</template>
