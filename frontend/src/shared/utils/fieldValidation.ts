type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
const errors = new WeakMap<HTMLElement, HTMLElement>()
const serverErrors = new Set<HTMLElement>()
let sequence = 0
let currentScope: ParentNode = document

function control(target: EventTarget | null): target is Control {
  return (
    target instanceof HTMLInputElement ||
    target instanceof HTMLSelectElement ||
    target instanceof HTMLTextAreaElement
  )
}
export function setFieldError(element: HTMLElement, message: string) {
  const previous = errors.get(element)
  if (!message) {
    previous?.remove()
    errors.delete(element)
    serverErrors.delete(element)
    element.removeAttribute('aria-invalid')
    if (previous) {
      const ids = (element.getAttribute('aria-describedby') || '')
        .split(' ')
        .filter((id) => id && id !== previous.id)
      if (ids.length) element.setAttribute('aria-describedby', ids.join(' '))
      else element.removeAttribute('aria-describedby')
    }
    element.classList.remove('field-invalid')
    return
  }
  element.classList.add('field-invalid')
  element.setAttribute('aria-invalid', 'true')
  if (previous) {
    previous.textContent = message
    return
  }
  const hint = document.createElement('small')
  hint.className = 'field-error inline-validation'
  hint.id = 'field-validation-' + ++sequence
  hint.setAttribute('role', 'alert')
  hint.textContent = message
  element.setAttribute(
    'aria-describedby',
    [element.getAttribute('aria-describedby'), hint.id].filter(Boolean).join(' '),
  )
  const host = element.closest('.input-unit, .flink-image-select, .password-input') || element
  host.insertAdjacentElement('afterend', hint)
  errors.set(element, hint)
}
function validateField(element: Control) {
  if (element.disabled || !element.willValidate) {
    setFieldError(element, '')
    return
  }
  const v = element.validity
  const message = v.customError
    ? element.validationMessage
    : v.valueMissing
      ? '此项不能为空'
      : v.typeMismatch || v.patternMismatch
        ? '格式不正确，请按字段说明填写'
        : v.rangeUnderflow
          ? '不能小于 ' + element.getAttribute('min')
          : v.rangeOverflow
            ? '不能大于 ' + element.getAttribute('max')
            : v.stepMismatch
              ? '请输入有效数值'
              : v.badInput
                ? '请输入有效数字'
                : v.tooLong
                  ? '内容超过最大长度'
                  : v.valid
                    ? ''
                    : element.validationMessage
  setFieldError(element, message)
}
export function validationScope() {
  return currentScope
}
const aliases: Record<string, string[]> = {
  name: ['作业名称', 'SQL 作业名称', '名称已存在', '填写名称'],
  id: ['标识', '用户名'],
  repository: ['镜像仓库'],
  version: ['镜像版本', 'Flink 版本'],
  imageId: ['运行镜像', '选择镜像'],
  entryClass: ['入口类'],
  jarURI: ['jarURI'],
  parallelism: ['并行度'],
  slots: ['Slot'],
  jmCpu: ['jmCpu', 'JobManager CPU'],
  tmCpu: ['tmCpu', 'TaskManager CPU'],
  jmMemory: ['JobManager 内存'],
  tmMemory: ['TaskManager 内存'],
  yaml: ['YAML', 'metadata.', 'spec.'],
  sql: ['SQL'],
  upgradeMode: ['last-state 模式', 'savepoint 模式', '升级策略'],
  flinkConfiguration: ['Flink 配置', 'flinkConfiguration'],
  podTemplate: ['PodTemplate', 'podTemplate'],
  value: ['密钥值', 'JSON'],
  secretName: ['Secret 名称'],
  secretKey: ['Secret 名称或键'],
  uri: ['制品地址', 'HTTP/HTTPS/S3'],
  sha256: ['SHA-256'],
  filename: ['文件名'],
  password: ['密码'],
  passwordRef: ['密码引用'],
  host: ['主机', 'host'],
  port: ['端口', 'port'],
  brokers: ['Kafka', 'brokers'],
  columns: ['字段', '主键', 'columns'],
  tableName: ['SQL 表名'],
  table: ['数据库或表名'],
  database: ['数据库或表名'],
  defaultDatabase: ['数据库名称'],
  hiveConfDir: ['Hive 配置目录'],
  warehouse: ['Warehouse'],
  groupId: ['消费组'],
  topic: ['Topic'],
  schema: ['Schema'],
  sslMode: ['TLS'],
  startupMode: ['起始位置'],
  startupTimestamp: ['起始时间'],
  format: ['格式'],
  watermarkColumn: ['Watermark 必须'],
  watermarkDelaySeconds: ['Watermark 延迟'],
  rowsPerSecond: ['生成速率'],
  path: ['文件路径', '路径需为'],
  dataSourceId: ['JDBC Catalog 必须', '数据源密码', 'TLS'],
  connectorType: ['连接器', '数据源类型'],
  role: ['角色'],
  enabled: ['禁用自身'],
}
export function showValidationError(message: string, scope: ParentNode = currentScope): string {
  if (scope instanceof HTMLElement && !scope.isConnected) return message
  const fields = [...scope.querySelectorAll<HTMLElement>('[data-field]')]
  let explicit = message
    .match(/^([a-zA-Z][\w.]*)\s*:/)?.[1]
    ?.split('.')
    .pop()
  if (explicit === 'downloaderImage' && !fields.some((field) => field.dataset.field === explicit))
    explicit = 'imageId'
  for (const field of fields) {
    const key = field.dataset.field || ''
    if (
      explicit
        ? key !== explicit
        : !(aliases[key] || [key]).some((word) => word && message.includes(word))
    )
      continue
    const target = field.matches(
      'input,select,textarea,.code-editor,.flink-config-editor,.artifact-picker',
    )
      ? field
      : field.querySelector<HTMLElement>(
          'input,select,textarea,.code-editor,.flink-config-editor',
        ) || field
    if (target instanceof HTMLInputElement && target.disabled) continue
    setFieldError(target, message)
    serverErrors.add(target)
  }
  return message
}
export function clearValidationErrors(scope: ParentNode = document) {
  for (const field of [...serverErrors]) {
    if (
      !field.isConnected ||
      scope === document ||
      (scope instanceof Node && scope.contains(field))
    )
      setFieldError(field, '')
  }
}
export function installFieldValidation() {
  document.addEventListener(
    'pointerdown',
    (event) => {
      if (event.target instanceof HTMLElement)
        currentScope =
          event.target.closest('form') ||
          event.target.closest('.form-panel, .sql-workbench, dialog') ||
          document
    },
    true,
  )
  document.addEventListener('focusin', (event) => {
    if (event.target instanceof HTMLElement)
      currentScope =
        event.target.closest('form') ||
        event.target.closest('.form-panel, .sql-workbench, dialog') ||
        document
  })
  document.addEventListener(
    'invalid',
    (event) => {
      event.preventDefault()
      if (control(event.target)) validateField(event.target)
    },
    true,
  )
  document.addEventListener(
    'focusout',
    (event) => {
      if (control(event.target)) validateField(event.target)
    },
    true,
  )
  for (const type of ['input', 'change'])
    document.addEventListener(
      type,
      (event) => {
        if (event.target instanceof HTMLElement)
          clearValidationErrors(
            event.target.closest('form, .sql-workbench, dialog') || currentScope,
          )
        if (control(event.target)) {
          if (errors.has(event.target)) validateField(event.target)
        } else if (event.target instanceof HTMLElement) {
          const editor = event.target.closest<HTMLElement>('.code-editor, .flink-config-editor')
          if (editor) setFieldError(editor, '')
        }
      },
      true,
    )
}
