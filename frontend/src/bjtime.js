// 北京时间（Asia/Shanghai，固定 UTC+8，无夏令时）工具集。
// 全站展示与查询条件统一使用北京时区，与后端容器 TZ=Asia/Shanghai 保持一致。

const BJ_OFFSET_MIN = 480

const pad = (n) => String(n).padStart(2, '0')

/** Date/epoch → 北京墙钟分量 */
export function bjParts(date) {
  const d = date instanceof Date ? date : new Date(date)
  const t = new Date(d.getTime() + BJ_OFFSET_MIN * 60000)
  return {
    y: t.getUTCFullYear(),
    m: t.getUTCMonth() + 1,
    d: t.getUTCDate(),
    hh: t.getUTCHours(),
    mm: t.getUTCMinutes(),
    ss: t.getUTCSeconds()
  }
}

/** 北京墙钟分量 → ISO 字符串（+08:00 结尾） */
export function bjIso(parts) {
  const epoch = Date.UTC(parts.y, parts.m - 1, parts.d, parts.hh, parts.mm, parts.ss || 0) - BJ_OFFSET_MIN * 60000
  const t = new Date(epoch + BJ_OFFSET_MIN * 60000)
  void t
  return `${parts.y}-${pad(parts.m)}-${pad(parts.d)}T${pad(parts.hh)}:${pad(parts.mm)}:${pad(parts.ss || 0)}+08:00`
}

/** Date/epoch → 北京 ISO 字符串 */
export function toBjIso(date) {
  return bjIso(bjParts(date))
}

/** 当前时间（北京）ISO 字符串 */
export function bjNowIso() {
  return toBjIso(new Date())
}

/** N 分钟前（北京）ISO 字符串 */
export function minutesAgoBjIso(minutes) {
  return toBjIso(new Date(Date.now() - minutes * 60000))
}

/** ISO/epoch → 北京展示文本 "YYYY-MM-DD HH:mm:ss"；非法输入原样返回 */
export function fmtBj(value) {
  if (!value) return '-'
  const d = value instanceof Date ? value : new Date(value)
  if (isNaN(d.getTime())) return String(value)
  const p = bjParts(d)
  return `${p.y}-${pad(p.m)}-${pad(p.d)} ${pad(p.hh)}:${pad(p.mm)}:${pad(p.ss)}`
}

/** ISO/epoch → 北京短文本 "HH:mm:ss" */
export function fmtBjTime(value) {
  if (!value) return '-'
  const d = value instanceof Date ? value : new Date(value)
  if (isNaN(d.getTime())) return String(value)
  const p = bjParts(d)
  return `${pad(p.hh)}:${pad(p.mm)}:${pad(p.ss)}`
}

/** ISO 字符串 → 北京墙钟分量（供选择器回显）；空值返回当前时间分量的秒位清零 */
export function isoToBjParts(iso) {
  if (!iso) {
    const now = bjParts(new Date())
    return { ...now, ss: 0 }
  }
  const p = bjParts(new Date(iso))
  return { ...p, ss: 0 }
}
