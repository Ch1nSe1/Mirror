// 审边台（独立版，2026-11-20）
// 复用插件里的 review-server（同一份页面与代理逻辑），不依赖 DSH 重启。
// 用法：node tools/review-ui/server.mjs [--port 8791]

import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const pluginDir = path.join(os.homedir(), '.dsh', 'profiles', 'desktop', 'node_modules', 'dsh-memore-bridge')
const mod = await import(pathToFileURL(path.join(pluginDir, 'review-server.js')).href)
const { pageHtml, registerReview } = mod

const portArgIndex = process.argv.indexOf('--port')
const PORT = portArgIndex >= 0 ? Number(process.argv[portArgIndex + 1]) || 8791 : 8791

function readConfig() {
  const files = [
    path.join(os.homedir(), '.dsh', 'memore-agent.json'),
    process.env.MEMORE_AGENT_CONFIG || '',
  ].filter(Boolean)
  for (const f of files) {
    try {
      const raw = JSON.parse(fs.readFileSync(f, 'utf8'))
      const base = String(raw.base || raw.lastLan || '').trim()
        || (Array.isArray(raw.endpoints) ? String(raw.endpoints[0] || '') : '')
      const token = String(raw.token || '').trim()
      if (base) return { base: base.replace(/\/+$/, ''), token }
    } catch { /* next */ }
  }
  throw new Error('读不到地址：~/.dsh/memore-agent.json 里没有 base/lastLan/endpoints')
}

function requestPhone(method, pathAndQuery, payload) {
  const { base, token } = readConfig()
  const url = new URL(pathAndQuery, base)
  const body = payload === undefined ? '' : JSON.stringify(payload)
  return new Promise((resolve, reject) => {
    const req = http.request(url, {
      method,
      headers: {
        'X-Memore-Token': token,
        'X-Memore-Client': os.hostname().slice(0, 40),
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
      },
      timeout: 20000,
    }, (res) => {
      let data = ''
      res.setEncoding('utf8')
      res.on('data', (d) => { data += d })
      res.on('end', () => {
        try {
          const j = JSON.parse(data)
          if (j && j.ok === false) return reject(new Error(j.error || '手机返回失败'))
          resolve(j)
        } catch {
          reject(new Error('返回不是 JSON: ' + data.slice(0, 120)))
        }
      })
    })
    req.on('error', reject)
    req.on('timeout', () => { req.destroy(); reject(new Error('手机没响应')) })
    if (body) req.write(body)
    req.end()
  })
}

const get = (p) => requestPhone('GET', p).then((j) => JSON.stringify(j))
const post = (p, payload) => requestPhone('POST', p, payload).then((j) => JSON.stringify(j))

function writeJson(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(body))
}

function isLoopbackRequest(req) {
  const a = req.socket?.remoteAddress || ''
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1'
}

const logPanel = (line) => {
  try {
    fs.appendFileSync(path.join(os.homedir(), '.dsh', 'memore-review.log'), new Date().toISOString() + ' ' + line + '\n')
  } catch { /* ignore */ }
}

const routes = []
const scope = {
  effect: (fn) => fn(),
  webServer: { register: (r) => { routes.push(r); return r } },
}
registerReview({ scope, get, post, writeJson, isLoopbackRequest, logPanel })

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x')
  if (url.pathname === '/') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
    res.end(pageHtml())
    return
  }
  const route = routes.find((r) => r.path === url.pathname)
  if (!route) { writeJson(res, 404, { ok: false, error: 'not found' }); return }
  try { await route.handler(req, res) } catch (e) { writeJson(res, 500, { ok: false, error: String(e?.message ?? e) }) }
}).listen(PORT, '127.0.0.1', () => {
  console.log('review ui: http://127.0.0.1:' + PORT + '/')
})
