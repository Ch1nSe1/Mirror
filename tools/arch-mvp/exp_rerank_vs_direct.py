#!/usr/bin/env python3
"""
召回+重排 vs 整月直读（2026-11-19，真实 API 试验）

问题：在几百条个人记录上，检索到底值不值？
做法：取同一个月的记录，挑 5 条查询；对每条查询比较两种方式——
  R（召回+重排）：向量 + FTS5 + 图 PPR 融合取 20 条候选，让模型从候选里挑同一条线（≤3）；
  D（直读）：把这个月全部记录直接塞进上下文，让模型挑（≤3）。
两边的提示词只差在"候选从哪来"，判据一致；输出对比。

用法：
  python exp_rerank_vs_direct.py --db <memore.db> --month 2026-09 --queries 5
密钥从环境变量 DEEPSEEK_API_KEY 读（不要写进文件）。
"""
import argparse
import json
import math
import os
import sqlite3
import struct
import sys
import time
import urllib.request
import collections

ENDPOINT = os.environ.get("DEEPSEEK_ENDPOINT", "https://api.deepseek.com/v1")
MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-flash")


def call_model(messages, max_tokens=2500, timeout=240):
    key = os.environ.get("DEEPSEEK_API_KEY", "")
    if not key:
        raise RuntimeError("缺少 DEEPSEEK_API_KEY")
    body = json.dumps({
        "model": MODEL,
        "messages": messages,
        "temperature": 0,
        "max_tokens": max_tokens,
        "stream": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        ENDPOINT.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def load(db_path, month):
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    rows = con.execute(
        "SELECT id, type, content, created_at FROM records WHERE is_deleted=0 AND content<>''"
    ).fetchall()
    texts = {r["id"]: r["content"] for r in rows}
    info = {r["id"]: (r["type"], r["created_at"]) for r in rows}

    vecs = {}
    for r in con.execute("SELECT record_id, vec, dim FROM record_vectors"):
        rid = r["record_id"]
        if rid not in texts:
            continue
        try:
            arr = struct.unpack(f"<{r['dim']}f", r["vec"][: 4 * r["dim"]])
        except Exception:
            continue
        n = math.sqrt(sum(x * x for x in arr)) or 1.0
        vecs[rid] = [x / n for x in arr]

    g = collections.defaultdict(dict)
    for r in con.execute("SELECT from_id,to_id,type,COALESCE(score,0) AS sc FROM record_relations WHERE status='ACTIVE'"):
        f, t = r["from_id"], r["to_id"]
        if f not in texts or t not in texts or f == t:
            continue
        w = 1.0 + (float(r["sc"]) if r["type"] == "LINK" else 0.5)
        g[f][t] = g[f].get(t, 0) + w
        g[t][f] = g[t].get(f, 0) + w

    import datetime
    def month_of(rid):
        return datetime.datetime.fromtimestamp(info[rid][1] / 1000).strftime("%Y-%m")
    month_ids = [rid for rid in texts if month_of(rid) == month]
    return con, texts, info, vecs, g, month_ids


def ppr(g, start, alpha=0.85, iters=20, k=20):
    if start not in g:
        return []
    nodes = list(g.keys())
    rank = {n: 0.0 for n in nodes}
    rank[start] = 1.0
    for _ in range(iters):
        new = {n: (1 - alpha) / max(1, len(nodes)) for n in nodes}
        for n in nodes:
            if rank[n] <= 0:
                continue
            nb = g.get(n, {})
            tot = sum(nb.values()) or 1.0
            for m, w in nb.items():
                new[m] = new.get(m, 0) + alpha * rank[n] * w / tot
        rank = new
    rank.pop(start, None)
    return [n for n, _ in sorted(rank.items(), key=lambda kv: kv[1], reverse=True)[:k]]


def vec_topk(vecs, start, k=20):
    v = vecs.get(start)
    if not v:
        return []
    sims = [(rid, sum(a * b for a, b in zip(v, o))) for rid, o in vecs.items() if rid != start]
    sims.sort(key=lambda x: -x[1])
    return [rid for rid, _ in sims[:k]]


def fts_topk(con, texts, start, k=20):
    try:
        rs = con.execute(
            "SELECT rowid FROM records_fts5 WHERE records_fts5 MATCH ? ORDER BY bm25(records_fts5) LIMIT ?",
            ('"' + texts[start][:60].replace('"', '""') + '"', k),
        ).fetchall()
    except Exception:
        return []
    ids = [r["rowid"] for r in rs]
    if not ids:
        return []
    out = []
    for row in con.execute(
        "SELECT id FROM records WHERE rowid IN (%s)" % ",".join("?" * len(ids)), ids
    ):
        out.append(row["id"])
    return out


def rrf(lists, k=60, top=20):
    score = collections.defaultdict(float)
    for lst in lists:
        for i, rid in enumerate(lst):
            score[rid] += 1.0 / (k + i + 1)
    return [r for r, _ in sorted(score.items(), key=lambda kv: -kv[1])[:top]]


JUDGE = (
    "你在帮一个人整理自己的记录。下面是【查询记录】，以及若干【候选记录】。\n"
    "只挑出与查询记录属于**同一条线**的记录：同一件事的推进、同一个反复出现的模式、"
    "或同一段关系的不同阶段；不要挑只是在讲同一类事的。\n"
    "最多 3 条，一条都没有就返回空数组。每条给出：id 与一句依据（10 字内）。\n"
    "只输出 JSON：{\"picks\":[{\"id\":\"...\",\"why\":\"...\"}]}"
)


def prompt_rerank(qtext, cands):
    body = [JUDGE, "", "【查询记录】", qtext[:800], "", "【候选记录】"]
    for i, c in enumerate(cands, 1):
        body.append(f"{i}. id={c['id'][:8]} [{c['date']}] {c['text'][:220]}")
    return "\n".join(body)


def prompt_direct(qtext, month_records):
    body = [JUDGE, "", "【查询记录】", qtext[:800], "", "【这个月的全部记录】"]
    for r in month_records:
        body.append(f"- id={r['id'][:8]} [{r['date']}] {r['text'][:320]}")
    return "\n".join(body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--month", default="2026-09")
    ap.add_argument("--queries", type=int, default=5)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    con, texts, info, vecs, g, month_ids = load(args.db, args.month)
    import datetime

    # 查询：该月 150~700 字、有向量的记录里挑，尽量分散
    pool = sorted([rid for rid in month_ids if 150 <= len(texts[rid]) <= 700 and rid in vecs],
                  key=lambda rid: info[rid][1])
    step = max(1, len(pool) // args.queries)
    queries = pool[::step][: args.queries]

    month_records = [
        {"id": rid, "date": datetime.datetime.fromtimestamp(info[rid][1] / 1000).strftime("%Y-%m-%d"),
         "text": texts[rid]}
        for rid in sorted(month_ids, key=lambda r: info[r][1])
    ]

    results = []
    for q in queries:
        cand_ids = rrf([vec_topk(vecs, q), fts_topk(con, texts, q), ppr(g, q)], top=20)
        cand_ids = [c for c in cand_ids if c != q][:20]
        cands = [{"id": c,
                  "date": datetime.datetime.fromtimestamp(info[c][1] / 1000).strftime("%Y-%m-%d"),
                  "text": texts[c]} for c in cand_ids]

        t0 = time.time()
        r_raw = call_model([{"role": "user", "content": prompt_rerank(texts[q], cands)}], max_tokens=2000)
        r_sec = time.time() - t0
        t0 = time.time()
        d_raw = call_model([{"role": "user", "content": prompt_direct(texts[q], month_records)}], max_tokens=2500)
        d_sec = time.time() - t0

        def parse(raw):
            try:
                s = raw[raw.find("{"): raw.rfind("}") + 1]
                return json.loads(s).get("picks", [])
            except Exception:
                return []

        results.append({
            "query": q[:8],
            "query_text": texts[q][:200],
            "recall_rerank": {"picks": parse(r_raw), "seconds": round(r_sec, 1)},
            "direct_read": {"picks": parse(d_raw), "seconds": round(d_sec, 1)},
        })
        print(f"[{q[:8]}] rerank={len(parse(r_raw))} 条 {r_sec:.1f}s | direct={len(parse(d_raw))} 条 {d_sec:.1f}s")

    out = {"month": args.month, "results": results}
    text = json.dumps(out, ensure_ascii=False, indent=1)
    if args.out:
        open(args.out, "w", encoding="utf-8").write(text)
    print(text)


if __name__ == "__main__":
    main()
