const KEY_STORAGE = 'tls_api_key'

export function getApiKey() {
  return localStorage.getItem(KEY_STORAGE)
    || import.meta.env.VITE_DEFAULT_API_KEY
    || ''
}

export function setApiKey(key) {
  localStorage.setItem(KEY_STORAGE, key.trim())
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
