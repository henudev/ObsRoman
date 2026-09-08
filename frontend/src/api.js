// 前端统一使用内置管理员默认 Key（顶栏无需再填写 Key）；
// 部署时可通过构建环境变量 VITE_DEFAULT_API_KEY 覆盖，默认 admin:admin 与服务端内置 key 一致。
export function getApiKey() {
  return import.meta.env.VITE_DEFAULT_API_KEY || 'admin:admin'
}

export class ApiError extends Error {
  constructor(code, message, status) {
    super(message)
    this.code = code
    this.status = status
  }
}

async function parse(resp) {
  const text = await resp.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch (e) {
    body = null
  }
  return { resp, body }
}

export async function apiRequest(method, path, body) {
  const { resp, body: parsed } = await parse(await fetch(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getApiKey()}`
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  }))
  if (parsed && parsed.code !== 0) {
    throw new ApiError(parsed.code, parsed.message || 'request failed', resp.status)
  }
  if (!resp.ok) {
    const message = parsed && parsed.message ? parsed.message : `HTTP ${resp.status}`
    throw new ApiError(parsed ? parsed.code : resp.status, message, resp.status)
  }
  return parsed ? parsed.data : null
}

/** 导出：返回 { blob, rows }，rows 来自 X-Export-Rows 响应头（流式写出前已知的行数） */
export async function apiExport(payload, format) {
  const resp = await fetch('/api/v1/logs/export', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${getApiKey()}`
    },
    body: JSON.stringify({ ...payload, format })
  })
  if (!resp.ok) {
    const { body: parsed } = await parse(resp)
    const message = parsed && parsed.message ? parsed.message : `HTTP ${resp.status}`
    throw new ApiError(parsed ? parsed.code : resp.status, message, resp.status)
  }
  const blob = await resp.blob()
  const rows = Number(resp.headers.get('X-Export-Rows'))
  return { blob, rows: Number.isFinite(rows) ? rows : null }
}

export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

// ---------- API Key 管理（需 key:admin 权限） ----------

export async function apiKeysList() {
  return apiRequest('GET', '/api/v1/api-keys')
}

export async function apiKeyCreate(payload) {
  return apiRequest('POST', '/api/v1/api-keys', payload)
}

export async function apiKeyUpdate(ak, payload) {
  return apiRequest('PUT', `/api/v1/api-keys/${encodeURIComponent(ak)}`, payload)
}

export async function apiKeyDelete(ak) {
  return apiRequest('DELETE', `/api/v1/api-keys/${encodeURIComponent(ak)}`)
}
