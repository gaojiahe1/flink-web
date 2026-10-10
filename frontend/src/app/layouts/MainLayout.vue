<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Activity, Settings, PanelLeftClose, PanelLeftOpen, LogOut } from '@lucide/vue'
import { navigationGroups } from '../navigation'
import { currentUser, logout } from '../../modules/permission/stores/auth'
import { systemInfo } from '../../modules/system/stores/system'
import { request } from '../../shared/api/http'
import type { SystemInfo } from '../../shared/types/api'
const router = useRouter(),
  route = useRoute(),
  error = ref('')
const collapsed = ref(localStorage.getItem('flink-sidebar-collapsed') === 'true')
function toggle() {
  collapsed.value = !collapsed.value
  localStorage.setItem('flink-sidebar-collapsed', String(collapsed.value))
}
const links = navigationGroups.flatMap((group) => group.items)
const visibleGroups = computed(() =>
  navigationGroups
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) => !item.adminOnly || currentUser.value?.roles.includes('ROLE_ADMIN'),
      ),
    }))
    .filter((group) => group.items.length),
)
const title = computed(
  () => links.find((link) => route.path.startsWith(link.path))?.title || '系统设置',
)
onMounted(async () => {
  try {
    systemInfo.value = await request<SystemInfo>('/api/system')
  } catch (e) {
    error.value = (e as Error).message
  }
})
async function signOut() {
  try {
    await logout()
    await router.push('/login')
  } catch (e) {
    error.value = (e as Error).message
  }
}
</script>
<template>
  <div class="app-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <aside class="sidebar">
      <RouterLink to="/jobs" class="brand" title="Flink Platform"
        ><span class="brand-mark"><Activity :size="24" /></span
        ><span class="brand-copy"
          >Flink <span class="brand-light">Platform</span><small>REALTIME COMPUTE</small></span
        ></RouterLink
      >
      <nav aria-label="主导航" class="navigation-groups">
        <section v-for="group in visibleGroups" :key="group.title" class="navigation-group">
          <div class="nav-label">{{ group.title }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :title="item.title"
            :aria-label="item.title"
            ><component :is="item.icon" :size="18" class="nav-icon" aria-hidden="true" /><span
              class="nav-text"
              >{{ item.title }}</span
            ></RouterLink
          >
        </section>
      </nav>
      <div class="sidebar-bottom">
        <RouterLink to="/settings" class="nav-item" title="系统设置" aria-label="系统设置"
          ><Settings :size="18" class="nav-icon" aria-hidden="true" /><span class="nav-text"
            >系统设置</span
          ></RouterLink
        >
        <div class="workspace">
          <span class="status-dot"></span>
          <div>
            当前 Namespace<small>{{ systemInfo?.namespace || '读取配置中' }}</small>
          </div>
        </div>
      </div>
    </aside>
    <section class="main-shell">
      <header class="topbar">
        <div class="header-location">
          <button
            class="icon-button"
            :aria-label="collapsed ? '展开导航' : '收起导航'"
            :aria-expanded="!collapsed"
            @click="toggle"
          >
            <PanelLeftOpen v-if="collapsed" :size="19" /><PanelLeftClose v-else :size="19" />
          </button>
          <div class="breadcrumb">
            工作空间 <span>/</span><strong>{{ title }}</strong>
          </div>
        </div>
        <div class="topbar-right">
          <span class="namespace-tag">{{ systemInfo?.namespace || '—' }}</span
          ><span class="version-tag">v{{ systemInfo?.version || '0.3.0' }}</span
          ><span class="avatar">{{ currentUser?.username.slice(0, 1).toUpperCase() }}</span
          ><span>{{ currentUser?.username }}</span
          ><button class="icon-button" aria-label="退出" title="退出" @click="signOut">
            <LogOut :size="16" />
          </button>
        </div>
      </header>
      <main class="content">
        <div v-if="error" class="notice error">{{ error }}</div>
        <RouterView />
      </main>
      <footer>Flink Platform<span>按需查询 · 配置驱动 · 独立工作空间</span></footer>
    </section>
  </div>
</template>
