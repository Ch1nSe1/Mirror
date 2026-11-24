// 隐私门禁扫描器（2026-11-21）
//
// 目的：不让作者的真实数据（人名、正文句子、机器名）进入仓库、提示词、导出物。
// 词表存在**仓库之外**（~/.dsh/private-terms.txt 或 CI 的 PRIVATE_TERMS secret），
// 因为把词表提交进仓库，扫描器本身就成了泄露源。
//
// 用法：
//   node scripts/privacy-scan.mjs            # 只扫暂存区（pre-commit 用）
//   node scripts/privacy-scan.mjs --all      # 扫全部已跟踪文件（CI 用）
//   node scripts/privacy-scan.mjs --all --exclude-src docs  # 额外排除目录
//
// 退出码：命中 1，干净 0，读不到词表也返回 0（本地没词表时不阻塞开发，但会警告）。

import { execFileSync } from 'node:child_process'
import { readFileSync, existsSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const ROOT = process.cwd()
const args = process.argv.slice(2)
const mode = args.includes('--all') ? 'all' : 'staged'

// 额外排除目录（--exclude-src a b c）
const extraExcludes = []
{
  const i = args.indexOf('--exclude-src')
  if (i >= 0) extraExcludes.push(...args.slice(i + 1).filter((a) => !a.startsWith('--')))
}

// ---- 词表 ----
function loadTerms() {
  const terms = []
  const env = (process.env.PRIVATE_TERMS || '').trim()
  if (env) terms.push(...env.split(/\r?\n/))
  const file = join(homedir(), '.dsh', 'private-terms.txt')
  if (existsSync(file)) {
    terms.push(
      ...readFileSync(file, 'utf8')
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith('#'))
    )
  }
  return [...new Set(terms.filter(Boolean))]
}

// ---- 文件清单 ----
function listFiles() {
  const gitArgs =
    mode === 'all'
      ? ['ls-files', '-z']
      : ['diff', '--cached', '--name-only', '--diff-filter=ACM', '-z']
  const out = execFileSync('git', gitArgs, { cwd: ROOT, encoding: 'utf8' })
  return out.split('\0').filter(Boolean)
}

const SKIP_PREFIXES = [
  'build/', '.gradle/', '.idea/', 'app/build/', 'node_modules/', '.git/',
  'app/src/main/assets/sounds/'
]

function shouldSkip(path) {
  if (SKIP_PREFIXES.some((p) => path.startsWith(p) || path.includes('/' + p))) return true
  if (extraExcludes.some((p) => path === p || path.startsWith(p + '/'))) return true
  return false
}

const MAX_BYTES = 2 * 1024 * 1024

function scan() {
  const terms = loadTerms()
  if (terms.length === 0) {
    console.warn('[privacy-scan] 没有词表（~/.dsh/private-terms.txt 或 PRIVATE_TERMS），跳过内容检查')
  }
  const files = listFiles().filter((f) => !shouldSkip(f))
  const hits = []
  for (const file of files) {
    let stat
    try {
      stat = statSync(join(ROOT, file))
    } catch {
      continue
    }
    if (!stat.isFile() || stat.size > MAX_BYTES) continue
    let text
    try {
      text = readFileSync(join(ROOT, file), 'utf8')
    } catch {
      continue
    }
    if (text.includes('\u0000')) continue // 二进制
    const lines = text.split(/\r?\n/)
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      for (const term of terms) {
        if (line.includes(term)) {
          hits.push({ file, line: i + 1, term, preview: line.trim().slice(0, 120) })
        }
      }
    }
  }
  return hits
}

const hits = scan()
if (hits.length > 0) {
  console.error(`\n[privacy-scan] 发现 ${hits.length} 处私人内容，已阻止：\n`)
  for (const h of hits) {
    console.error(`  ${h.file}:${h.line}  ← «${h.term}»`)
    console.error(`      ${h.preview}`)
  }
  console.error('\n换用中性占位（对象名 / Alice / 示例设备），或把该行移出仓库。\n')
  process.exit(1)
}
console.log(`[privacy-scan] ${mode === 'all' ? '全部已跟踪文件' : '暂存区'}：干净`)
