import * as echarts from 'echarts'

export function useChart() {
  let instance = null

  return {
    mount(el, option, onClick) {
      if (!el) return
      if (!instance) {
        instance = echarts.init(el)
        if (onClick) {
          instance.on('click', (params) => onClick(params))
        }
      }
      instance.setOption(option, true)
      instance.resize()
    },
    resize() {
      instance && instance.resize()
    },
    dispose() {
      if (instance) {
        instance.dispose()
        instance = null
      }
    }
  }
}

// 莫兰迪蓝系列：低饱和、统一的蓝灰基调
export const PALETTE = {
  primary: '#7d97ad',
  primaryDeep: '#5f7d95',
  primaryLight: '#a9bcca',
  bgSoft: '#eef1f4'
}

export const LEVEL_COLORS = {
  TRACE: '#b3bfc7',
  DEBUG: '#93a5b1',
  INFO: '#7d97ad',
  WARN: '#c2ab84',
  ERROR: '#b07a7e',
  FATAL: '#8a5a5e'
}

export function fmtNumber(n) {
  if (n === null || n === undefined) return '-'
  return Number(n).toLocaleString('en-US')
}
