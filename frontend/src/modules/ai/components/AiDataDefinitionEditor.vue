<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ modelValue: string; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [string] }>()
type Definition = Record<string, any> & { columns: Record<string, any>[] }
const definitions = computed<Definition[]>(() => {
  try {
    const value = JSON.parse(props.modelValue)
    return Array.isArray(value)
      ? value
          .filter((item) => item && typeof item === 'object' && !Array.isArray(item))
          .map((item) => ({
            ...item,
            columns: Array.isArray(item.columns)
              ? item.columns.filter(
                  (field: unknown) => field && typeof field === 'object' && !Array.isArray(field),
                )
              : [],
          }))
      : []
  } catch {
    return []
  }
})
function update(index: number, key: string, value: unknown) {
  const next = structuredClone(definitions.value)
  next[index]![key] = value
  emit('update:modelValue', JSON.stringify(next, null, 2))
}
function column(index: number, row: number, key: string, value: unknown) {
  const next = structuredClone(definitions.value)
  next[index]!.columns[row][key] = value
  emit('update:modelValue', JSON.stringify(next, null, 2))
}
function addColumn(index: number) {
  const next = structuredClone(definitions.value)
  next[index]!.columns ??= []
  next[index]!.columns.push({ name: '', type: 'STRING', primaryKey: false, nullable: true })
  emit('update:modelValue', JSON.stringify(next, null, 2))
}
function remove(index: number, row?: number) {
  const next = structuredClone(definitions.value)
  if (row === undefined) next.splice(index, 1)
  else next[index]!.columns.splice(row, 1)
  emit('update:modelValue', JSON.stringify(next, null, 2))
}
const text = (event: Event) => (event.target as HTMLInputElement).value
const checked = (event: Event) => (event.target as HTMLInputElement).checked
function parameters(
  connector: string,
): { key: string; label: string; placeholder?: string; number?: boolean }[] {
  if (['kafka', 'upsert-kafka'].includes(connector))
    return [
      { key: 'brokers', label: 'Kafka 地址', placeholder: 'broker:9092' },
      { key: 'topic', label: 'Topic' },
      { key: 'format', label: '消息格式', placeholder: 'json / csv / raw' },
      ...(connector === 'kafka' ? [{ key: 'groupId', label: '消费组' }] : []),
    ]
  if (['mysql', 'postgresql'].includes(connector))
    return [
      { key: 'host', label: '数据库主机' },
      { key: 'port', label: '端口' },
      { key: 'database', label: '数据库' },
      { key: 'table', label: '物理表名' },
      { key: 'username', label: '数据库用户名' },
      { key: 'passwordRef', label: '密码配置引用', placeholder: '配置管理中的密钥标识' },
      ...(connector === 'postgresql'
        ? [{ key: 'schema', label: 'Schema', placeholder: 'public' }]
        : []),
    ]
  if (connector === 'filesystem')
    return [
      { key: 'path', label: '文件路径', placeholder: 's3://bucket/path' },
      { key: 'format', label: '文件格式', placeholder: 'json / csv / parquet' },
    ]
  if (connector === 'datagen')
    return [{ key: 'rowsPerSecond', label: '每秒生成条数', number: true }]
  return []
}
</script>
<template>
  <details v-for="(definition, index) in definitions" :key="index" class="definition-form" open>
    <summary>
      {{ definition.tableName || '未命名表' }} · {{ definition.connectorType || '请选择连接器' }}
    </summary>
    <label
      >SQL 表名<input
        :value="definition.tableName"
        :disabled="disabled"
        :aria-label="'表' + (index + 1) + '名称'"
        @input="update(index, 'tableName', text($event))"
    /></label>
    <label
      >Connector<select
        :value="definition.connectorType"
        :disabled="disabled"
        :aria-label="'表' + (index + 1) + '连接器'"
        @change="update(index, 'connectorType', text($event))"
      >
        <option
          v-for="value in [
            'kafka',
            'upsert-kafka',
            'mysql',
            'postgresql',
            'filesystem',
            'datagen',
            'print',
          ]"
          :key="value"
        >
          {{ value }}
        </option>
      </select></label
    >
    <div class="definition-fields">
      <div v-for="(field, row) in definition.columns || []" :key="row" class="definition-column">
        <label
          >字段名<input
            :value="field.name"
            :disabled="disabled"
            :aria-label="'表' + (index + 1) + '字段' + (row + 1) + '名称'"
            @input="column(index, row, 'name', text($event))"
        /></label>
        <label
          >类型<input
            :value="field.type"
            :disabled="disabled"
            placeholder="STRING / DECIMAL(18,2)"
            :aria-label="'表' + (index + 1) + '字段' + (row + 1) + '类型'"
            @input="column(index, row, 'type', text($event))"
        /></label>
        <label class="definition-check"
          ><input
            type="checkbox"
            :checked="field.primaryKey"
            :disabled="disabled"
            @change="column(index, row, 'primaryKey', checked($event))"
          />主键</label
        >
        <button
          class="text-button danger"
          :disabled="disabled"
          :aria-label="'删除表' + (index + 1) + '字段' + (row + 1)"
          @click="remove(index, row)"
        >
          删除字段
        </button>
      </div>
    </div>
    <button class="button" :disabled="disabled" @click="addColumn(index)">添加字段</button>
    <div class="definition-parameters">
      <label v-for="field in parameters(definition.connectorType)" :key="field.key"
        >{{ field.label
        }}<input
          :value="definition[field.key]"
          :type="field.number ? 'number' : 'text'"
          :placeholder="field.placeholder"
          :disabled="disabled"
          @input="update(index, field.key, field.number ? Number(text($event)) : text($event))"
      /></label>
    </div>
    <template v-if="definition.connectorType !== 'print'">
      <label
        >水位线字段（可选）<input
          :value="definition.watermarkColumn"
          :disabled="disabled"
          placeholder="事件时间字段"
          @input="update(index, 'watermarkColumn', text($event))"
      /></label>
      <label v-if="definition.watermarkColumn"
        >水位线延迟（秒）<input
          :value="definition.watermarkDelaySeconds"
          :disabled="disabled"
          type="number"
          min="0"
          max="86400"
          @input="update(index, 'watermarkDelaySeconds', Number(text($event)))"
      /></label>
    </template>
    <button class="text-button danger" :disabled="disabled" @click="remove(index)">
      移除此表定义
    </button>
  </details>
</template>
<style scoped>
.definition-form {
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 12px;
  margin: 12px 0;
}
summary {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 12px;
  overflow-wrap: anywhere;
  cursor: pointer;
}
.definition-column {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 10px;
  border-top: 1px solid var(--line);
  padding: 10px 0;
}
.definition-check {
  display: flex;
  gap: 6px;
  align-items: center;
  font-weight: 400;
}
.definition-check input {
  width: auto;
}
.definition-column label {
  margin: 0;
  min-width: 0;
}
.definition-parameters {
  margin-top: 14px;
}
label {
  font-size: 12px;
}
button {
  font-size: 12px;
}
</style>
