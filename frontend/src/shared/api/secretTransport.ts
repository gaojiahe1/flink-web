import forge from 'node-forge'

export interface TransportChallenge {
  id: string
  algorithm: string
  publicKey: string
  purpose: 'login' | 'user-password' | 'config-secret'
  target: string
  expiresAt: string
}

/** HTTP 的 IP 入口没有 subtle，随机数仍只使用浏览器 CSPRNG，不降级 Math.random。 */
function randomBytes(length: number) {
  if (!globalThis.crypto?.getRandomValues)
    throw new Error('浏览器缺少安全随机数支持，请更换浏览器或使用 HTTPS')
  return String.fromCharCode(...crypto.getRandomValues(new Uint8Array(length)))
}

/** 标准 OAEP-SHA256 + AES-GCM 信封；随机种子由系统 CSPRNG 提供。 */
export function encryptWithChallenge(value: string, challenge: TransportChallenge) {
  if (challenge.algorithm !== 'RSA-OAEP-256+A256GCM')
    throw new Error('服务端加密协议不受支持，请刷新页面')
  const key = randomBytes(32),
    iv = randomBytes(12)
  const encoded = forge.util.encodeUtf8(value)
  if (encoded.length > 65536) throw new Error('密码或密钥最多支持 64 KiB')
  const cipher = forge.cipher.createCipher('AES-GCM', key)
  cipher.start({
    iv,
    additionalData: forge.util.encodeUtf8(
      [challenge.id, challenge.purpose, challenge.target].join('\n'),
    ),
    tagLength: 128,
  })
  cipher.update(forge.util.createBuffer(encoded))
  if (!cipher.finish()) throw new Error('密码加密失败，请重试')
  const publicKey = forge.pki.publicKeyFromAsn1(
    forge.asn1.fromDer(forge.util.decode64(challenge.publicKey)),
  )
  const encryptedKey = publicKey.encrypt(key, 'RSA-OAEP', {
    md: forge.md.sha256.create(),
    mgf1: { md: forge.md.sha256.create() },
    seed: randomBytes(32),
  })
  return (
    'fwenc1:' +
    JSON.stringify({
      id: challenge.id,
      key: forge.util.encode64(encryptedKey),
      iv: forge.util.encode64(iv),
      data: forge.util.encode64(cipher.output.getBytes() + cipher.mode.tag.getBytes()),
    })
  )
}

async function encrypt(value: string, purpose: TransportChallenge['purpose'], target: string) {
  const response = await fetch(
    '/api/auth/transport-key?' + new URLSearchParams({ purpose, target }),
    {
      credentials: 'same-origin',
      cache: 'no-store',
    },
  )
  const result = await response.json().catch(() => null)
  if (!response.ok || !result?.data)
    throw new Error(result?.error?.message || '无法获取密码加密参数，请重试')
  return encryptWithChallenge(value, result.data)
}

/** 只转换明确的密码/密钥写接口，不修改原始表单，也不重试失败的写请求。 */
export async function protectSensitiveRequest(
  url: string,
  init: RequestInit,
): Promise<RequestInit> {
  const method = (init.method || 'GET').toUpperCase()
  if (url === '/api/auth/login' && method === 'POST' && init.body instanceof URLSearchParams) {
    const body = new URLSearchParams(init.body)
    body.set(
      'password',
      await encrypt(body.get('password') || '', 'login', body.get('username') || ''),
    )
    return { ...init, body }
  }
  const user = /^\/api\/users\/([^/?]+)$/.exec(url)
  const config = /^\/api\/catalog\/configs\/([^/?]+)$/.exec(url)
  const model = /^\/api\/ai\/models\/([^/?]+)$/.exec(url)
  if (method !== 'PUT' || (!user && !config && !model) || typeof init.body !== 'string') return init
  const data = JSON.parse(init.body)
  if (user && typeof data.password === 'string' && data.password.length)
    data.password = await encrypt(data.password, 'user-password', decodeURIComponent(user[1]!))
  if (config && data.kind === 'secret' && typeof data.value === 'string' && data.value.length)
    data.value = await encrypt(data.value, 'config-secret', decodeURIComponent(config[1]!))
  if (model && typeof data.apiKey === 'string' && data.apiKey.length)
    data.apiKey = await encrypt(data.apiKey, 'config-secret', decodeURIComponent(model[1]!))
  return { ...init, body: JSON.stringify(data) }
}
