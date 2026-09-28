export const sourceTypes = [
  { value: 'mysql', label: 'MySQL · JDBC' },
  { value: 'postgresql', label: 'PostgreSQL · JDBC' },
  { value: 'kafka', label: 'Kafka' },
  { value: 'upsert-kafka', label: 'Upsert Kafka' },
  { value: 'filesystem', label: '文件系统 · S3 / HDFS' },
  { value: 'datagen', label: 'DataGen · 测试源' },
  { value: 'print', label: 'Print · 调试输出' },
]
export const isJdbc = (type: string) => ['mysql', 'postgresql'].includes(type)
export const isKafka = (type: string) => ['kafka', 'upsert-kafka'].includes(type)
export const configurationOnly = (type: string) => ['filesystem', 'datagen', 'print'].includes(type)
