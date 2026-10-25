<script setup lang="ts">
import { useOperator } from '../../../shared/composables/useOperator'
const { status, hint, refresh, disabled, remaining, busy } = useOperator()
import NetworkProbe from '../../data-management/components/NetworkProbe.vue'
import DownloaderSettings from '../components/DownloaderSettings.vue'
import PageHeader from '../../../shared/components/layout/PageHeader.vue'
import { systemInfo } from '../stores/system'
import { currentUser } from '../../permission/stores/auth'
</script>
<template>
  <PageHeader eyebrow="ENVIRONMENT"
    ><template #title>系统设置</template
    ><template #description>当前服务的部署配置与接入状态。</template></PageHeader
  >
  <NetworkProbe />
  <DownloaderSettings />
  <section v-if="currentUser?.roles.includes('ROLE_ADMIN')" class="panel form-panel operator-panel">
    <div class="panel-toolbar">
      <div>
        <h3>AI 模型服务</h3>
        <p class="muted">管理 SQL 助手、数据定义与作业报错分析使用的模型。</p>
      </div>
      <RouterLink class="button primary" to="/settings/models">管理模型服务</RouterLink>
    </div>
  </section>
  <section class="panel form-panel operator-panel">
    <div class="panel-toolbar">
      <h3>Flink Kubernetes Operator</h3>
      <button class="button" :disabled="disabled" @click="refresh">
        {{ busy ? '正在查询…' : remaining ? remaining + ' 秒后刷新' : '刷新 Operator' }}
      </button>
    </div>
    <p role="status">{{ hint }}</p>
    <dl v-if="status" class="settings-list">
      <dt>查询目标</dt>
      <dd>{{ status.namespace }} / {{ status.deployment }}</dd>
      <dt>Deployment 镜像</dt>
      <dd>{{ status.image || '—' }}</dd>
      <dt>就绪副本</dt>
      <dd>{{ status.readyReplicas ?? 0 }} / {{ status.replicas ?? 0 }}</dd>
      <dt>最近查询</dt>
      <dd>{{ new Date(status.queriedAt).toLocaleString() }}</dd>
      <dt v-if="status.compatibility.known">存量兼容范围</dt>
      <dd v-if="status.compatibility.known">
        {{ status.compatibility.accepted?.join(' / ') }}（含已弃用版本）
      </dd>
    </dl>
    <p class="muted">
      查询目标由 FLINK_WEB_OPERATOR_NAMESPACE / DEPLOYMENT / CONTAINER
      配置。首次进入查询一次，之后每 3 秒可手动刷新。
    </p>
    <div class="table-wrap" v-if="status?.pods?.length">
      <table>
        <thead>
          <tr>
            <th>Pod</th>
            <th>阶段</th>
            <th>就绪</th>
            <th>容器状态 / 重启次数</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="pod in status.pods" :key="pod.name">
            <td>{{ pod.name }}</td>
            <td>{{ pod.deleting ? 'Terminating' : pod.phase }}</td>
            <td>{{ pod.ready ? 'Ready' : 'NotReady' }}</td>
            <td>
              <div v-for="container in pod.containers" :key="container.name">
                {{ container.name }} · {{ container.state }} {{ container.reason }} ·
                {{ container.restarts }}
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else-if="status?.found" class="muted">
      {{ status.podMessage || '暂无匹配的 Operator Pod' }}
    </p>
  </section>
  <section class="panel form-panel">
    <h3>环境信息</h3>
    <dl class="settings-list">
      <dt>平台版本</dt>
      <dd>{{ systemInfo?.version || '—' }}</dd>
      <dt>目标 Namespace</dt>
      <dd>{{ systemInfo?.namespace || '—' }}</dd>
      <dt>持久化方式</dt>
      <dd>
        {{
          systemInfo?.storageType === 'local'
            ? '本地 SQLite 文件'
            : systemInfo?.storageType === 'mysql'
              ? 'MySQL'
              : '—'
        }}
      </dd>
      <dt>身份认证</dt>
      <dd>持久化用户与角色 · Session + CSRF</dd>
      <dt>后端运行时</dt>
      <dd>Spring Boot · JDK 17</dd>
      <dt>前端框架</dt>
      <dd>Vue 3 · TypeScript · Vite</dd>
    </dl>
    <div class="notice">
      部署参数由环境变量提供。Kubernetes、S3 和 Flink UI
      使用真实适配器；是否可用取决于当前环境的凭证、网络与资源配置。
    </div>
  </section>
</template>

<style scoped>
.operator-panel {
  margin-bottom: 16px;
}
dd {
  overflow-wrap: anywhere;
}
</style>
