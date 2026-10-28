<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import { aiApi, type AiModel } from '../../ai/api/ai'
import { currentUser } from '../../permission/stores/auth'
import { confirmDialog } from '../../../shared/utils/confirmDialog'
const admin = computed(() => currentUser.value?.roles.includes('ROLE_ADMIN'))
const models = ref<AiModel[]>([]),
  selected = ref(''),
  busy = ref(false),
  error = ref(''),
  message = ref('')
const defaults = () => ({
  id: '',
  name: '',
  model: '',
  baseUrl: '',
  revision: 0,
  apiKey: '',
  clearKey: false,
  enabled: true,
  external: true,
  privateNetwork: false,
  purposes: ['sql', 'definition', 'diagnosis'],
  timeoutSeconds: 90,
  maxTokens: 4096,
})
const form = reactive(defaults())
const query = ref('')
const filtered = computed(() =>
  models.value.filter((m) =>
    (m.name + ' ' + m.model + ' ' + m.baseUrl).toLowerCase().includes(query.value.toLowerCase()),
  ),
)
const tests = reactive<Record<string, { state: string; message: string }>>({})
function modelScope() {
  Object.assign(form, {
    id: selected.value || form.id || 'modelscope-qwen',
    name: form.name || 'Qwen3.5 27B',
    model: 'Qwen/Qwen3.5-27B',
    baseUrl: 'https://api-inference.modelscope.cn/v1',
    privateNetwork: false,
    external: true,
  })
}
async function load() {
  if (!admin.value) return
  try {
    models.value = await aiApi.models(true)
  } catch (cause) {
    error.value = (cause as Error).message
  }
}
function edit(model?: AiModel) {
  Object.assign(form, defaults(), model || {}, { apiKey: '', clearKey: false })
  selected.value = model?.id || ''
  error.value = ''
  message.value = ''
}
async function save() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const saved = await aiApi.saveModel(form.id, { ...form })
    delete tests[saved.id]
    await load()
    edit(saved)
    message.value = '模型配置已保存，密钥不会返回页面。'
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
    form.apiKey = ''
  }
}
async function test(model: AiModel) {
  tests[model.id] = { state: 'checking', message: '正在验证地址、鉴权和 JSON 响应…' }
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    message.value = (await aiApi.testModel(model.id)).message
    tests[model.id] = { state: 'success', message: message.value }
  } catch (cause) {
    error.value = (cause as Error).message
    tests[model.id] = { state: 'failed', message: error.value }
  } finally {
    busy.value = false
  }
}
async function remove(model: AiModel) {
  if (!(await confirmDialog('删除模型“' + model.name + '”？历史生成记录仍保留。'))) return
  busy.value = true
  try {
    await aiApi.deleteModel(model)
    await load()
    edit()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <PageHeader eyebrow="AI SERVICES"
    ><template #title>模型服务</template
    ><template #description
      >配置 SQL 助手与作业诊断使用的模型，支持兼容 Chat Completions 的服务。</template
    ><template #actions
      ><RouterLink class="button" to="/settings">返回系统设置</RouterLink></template
    ></PageHeader
  >
  <p v-if="!admin" class="notice error">仅管理员可维护模型服务。</p>
  <template v-else>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <div class="model-overview">
      <div>
        <h2>为 SQL 与运维选择合适的模型</h2>
        <p>统一管理服务连接，先测试，再在需求向导中选择。</p>
      </div>
      <div class="model-metrics">
        <strong>{{ models.length }}<small>已添加</small></strong
        ><strong>{{ models.filter((m) => m.enabled).length }}<small>已启用</small></strong>
      </div>
    </div>
    <div class="models-layout">
      <section class="panel model-list">
        <div class="panel-toolbar">
          <h3>已添加模型</h3>
          <button class="button primary" :disabled="busy" @click="edit()">添加模型</button>
        </div>
        <label class="model-search"
          >查找模型<input v-model="query" placeholder="按名称、模型或地址筛选"
        /></label>
        <p v-if="!models.length" class="empty-state">
          尚未添加模型。先填写服务地址和实际模型标识。
        </p>
        <article
          v-for="model in filtered"
          :key="model.id"
          :class="{ active: selected === model.id }"
        >
          <button class="model-name text-button" :disabled="busy" @click="edit(model)">
            {{ model.name }}
          </button>
          <span class="badge">{{ model.enabled ? '启用' : '停用' }}</span>
          <p>{{ model.model }} · {{ model.external ? '外部服务' : '私有服务' }}</p>
          <p class="model-endpoint">{{ model.baseUrl }}</p>
          <small>{{
            model.purposes
              .map(
                (p) => ({ sql: 'SQL 助手', definition: '数据定义', diagnosis: '作业诊断' })[p] || p,
              )
              .join(' / ')
          }}</small>
          <div class="toolbar-actions">
            <button class="text-button" :disabled="busy" @click="test(model)">测试连接</button
            ><button class="text-button danger" :disabled="busy" @click="remove(model)">
              删除
            </button>
          </div>
          <p
            v-if="tests[model.id]"
            class="model-test-result"
            :class="tests[model.id]!.state"
            role="status"
          >
            {{ tests[model.id]!.message }}
          </p>
        </article>
      </section>
      <form class="panel model-form" @submit.prevent="save">
        <div class="model-form-heading">
          <div>
            <h3>{{ selected ? '编辑模型' : '添加模型' }}</h3>
          </div>
          <span class="badge">{{ selected ? '已登记' : '新连接' }}</span>
        </div>
        <div class="provider-preset">
          <div>
            <strong>ModelScope · 魔搭</strong><small>快速填写 Qwen 服务地址与模型标识</small>
          </div>
          <button type="button" class="button" :disabled="busy" @click="modelScope">
            使用 ModelScope 配置
          </button>
        </div>
        <div class="model-grid">
          <label
            >配置标识<input
              v-model="form.id"
              :disabled="!!selected || busy"
              required
              pattern="[a-z0-9][a-z0-9_.-]{0,63}"
              placeholder="team-sql-model" /></label
          ><label
            >显示名称<input
              v-model="form.name"
              :disabled="busy"
              required
              maxlength="80"
              placeholder="团队 SQL 助手"
          /></label>
        </div>
        <label
          >Base URL<input
            v-model="form.baseUrl"
            :disabled="busy"
            required
            placeholder="https://model.example.com/v1"
          /><small>填写协议服务前缀；平台追加 /chat/completions，不跟随重定向。</small></label
        >
        <label
          >实际模型标识<input
            v-model="form.model"
            :disabled="busy"
            required
            placeholder="服务商提供的 model 标识"
        /></label>
        <label
          >API Key<input
            v-model="form.apiKey"
            aria-label="API Key"
            :disabled="busy"
            type="password"
            autocomplete="new-password"
            :placeholder="selected ? '已保存的密钥留空保留' : '无认证服务可留空'"
          /><small>使用一次性加密传输，并以平台主密钥加密保存。</small></label
        >
        <label v-if="selected" class="check-field"
          ><input v-model="form.clearKey" :disabled="busy" type="checkbox" />清除已保存密钥</label
        >
        <div class="model-grid">
          <label
            >调用超时（秒）<input
              v-model.number="form.timeoutSeconds"
              :disabled="busy"
              type="number"
              min="5"
              max="180" /></label
          ><label
            >最大输出 tokens<input
              v-model.number="form.maxTokens"
              :disabled="busy"
              type="number"
              min="256"
              max="8192"
          /></label>
        </div>
        <fieldset :disabled="busy">
          <legend>可用用途</legend>
          <label
            v-for="purpose in [
              { id: 'sql', label: 'SQL 生成 / 解释 / 修复' },
              { id: 'definition', label: '数据定义建议' },
              { id: 'diagnosis', label: '作业报错分析' },
            ]"
            :key="purpose.id"
            class="check-field"
            ><input v-model="form.purposes" type="checkbox" :value="purpose.id" />{{
              purpose.label
            }}</label
          >
        </fieldset>
        <label class="check-field"
          ><input v-model="form.enabled" :disabled="busy" type="checkbox" />启用此模型</label
        >
        <label class="check-field"
          ><input
            v-model="form.external"
            :disabled="busy"
            type="checkbox"
          />外部模型：每次调用需明确允许发送脱敏上下文</label
        >
        <label class="check-field"
          ><input
            v-model="form.privateNetwork"
            :disabled="busy"
            type="checkbox"
          />允许内网／本机地址与 HTTP（仅受信任的私有模型）</label
        >
        <p class="muted">
          链路本地、组播和云元数据地址始终拒绝。HTTP 不提供传输加密，公网服务请使用 HTTPS。
        </p>
        <button class="button primary" :disabled="busy || !form.purposes.length">
          {{ busy ? '正在处理…' : '保存模型' }}
        </button>
        <button
          v-if="selected"
          type="button"
          class="button model-test-button"
          :disabled="busy"
          @click="test(models.find((m) => m.id === selected)!)"
        >
          测试已保存配置
        </button>
        <p class="muted">测试只发送固定连接检查消息，不携带 SQL、表定义或作业日志。</p>
      </form>
    </div>
  </template>
</template>
<style scoped>
.models-layout {
  display: grid;
  grid-template-columns: minmax(260px, 0.8fr) minmax(0, 1.6fr);
  gap: 16px;
  align-items: start;
}
.model-list,
.model-form {
  padding: 16px;
  min-width: 0;
}
.model-list article {
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  margin-top: 10px;
  overflow-wrap: anywhere;
}
.model-overview {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  margin: 0 0 16px;
  padding: 12px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 8px;
}
.model-overview h2 {
  font-size: var(--font-subtitle);
  margin: 2px 0;
}
.model-overview p {
  color: var(--color-muted);
  margin: 0;
  font-size: var(--font-body);
}
.model-metrics {
  display: flex;
  gap: 32px;
}
.model-metrics strong {
  font-size: 22px;
  color: var(--color-primary);
  text-align: center;
}
.model-metrics small {
  display: block;
  font-size: var(--font-small);
  font-weight: 400;
  color: var(--color-muted);
}
.model-form-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.model-form-heading h3 {
  font-size: var(--font-section);
  margin: 0;
}
.provider-preset {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-soft);
  padding: 10px 12px;
  margin-bottom: 12px;
}
.provider-preset small {
  display: block;
  color: var(--color-muted);
  margin-top: 4px;
}
.model-endpoint {
  font-size: var(--font-small);
  font-family: var(--font-family-code);
  line-height: 1.7;
  color: var(--color-muted);
  overflow-wrap: anywhere;
}
.model-form label {
  margin-bottom: 12px;
}
.model-search {
  margin: 10px 0;
}
.model-test-button {
  margin-left: 10px;
}
.model-test-result {
  padding: 10px;
  border-radius: 6px;
  font-size: var(--font-small);
  white-space: pre-wrap;
}
.model-test-result.success {
  background: var(--color-success-soft);
  color: var(--color-success);
}
.model-test-result.failed {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}
.model-test-result.checking {
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 1.5px;
  color: var(--color-muted);
  font-weight: 600;
}
@media (max-width: 600px) {
  .model-overview {
    padding: 18px;
    align-items: flex-start;
    flex-direction: column;
  }
  .model-metrics {
    gap: 28px;
  }
  .provider-preset {
    align-items: flex-start;
    flex-direction: column;
  }
  .models-layout {
    gap: 14px;
  }
}
.model-list article.active {
  background: var(--color-primary-soft);
  border-radius: 6px;
}
.model-name {
  font-weight: 600;
  margin-right: 8px;
}
.model-list p {
  margin: 5px 0;
}
.model-list small {
  color: var(--color-muted);
}
.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
label {
  min-width: 0;
}
fieldset {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 16px;
}
.check-field {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--font-small);
  font-weight: 400;
}
.check-field input {
  width: auto;
  flex: none;
}
.muted,
small {
  font-size: var(--font-small);
}
@media (max-width: 850px) {
  .models-layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 480px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
}
</style>
