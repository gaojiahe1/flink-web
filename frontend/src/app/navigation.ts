import {
  Activity,
  Code,
  Layers,
  Box,
  KeyRound,
  FolderOpen,
  Users,
  ScrollText,
  Database,
} from '@lucide/vue'
import type { Component } from 'vue'

export interface NavigationItem {
  path: string
  title: string
  icon: Component
  adminOnly?: boolean
}
export interface NavigationGroup {
  title: string
  items: NavigationItem[]
}

export const navigationGroups: NavigationGroup[] = [
  {
    title: '开发与运维',
    items: [
      { path: '/sql', icon: Code, title: 'SQL 开发' },
      { path: '/jar', icon: Box, title: 'JAR 开发' },
      { path: '/jobs', icon: Activity, title: '作业管理' },
      { path: '/sessions', icon: Layers, title: 'Session 集群' },
    ],
  },
  {
    title: '资源中心',
    items: [
      { path: '/templates', icon: Layers, title: '作业模板' },
      { path: '/images', icon: Box, title: '运行镜像' },
      { path: '/configs', icon: KeyRound, title: '配置管理' },
      { path: '/artifacts', icon: FolderOpen, title: '文件与制品' },
      { path: '/catalogs', icon: Database, title: 'Catalog 管理' },
    ],
  },
  {
    title: '权限与治理',
    items: [
      { path: '/users', icon: Users, title: '用户与权限', adminOnly: true },
      { path: '/audit', icon: ScrollText, title: '操作审计', adminOnly: true },
    ],
  },
]
