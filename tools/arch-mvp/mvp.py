#!/usr/bin/env python3
"""
Mirror 新架构 MVP（2026-11-19）

目的：在电脑上、用真实数据（321 条记录 / 800 条关系 / 993 个向量块）跑一遍
「事件日志 + 投影 + 图检索」这条新架构，拿到可比较的数字。

它不改 App 的任何代码，只验证四件事：
  1. 事件回填：把现有表转成 append-only 事件流，要多久、多大；
  2. 投影重建：从事件重放出 records/relations/analysis，结果和旧库是否一致、要多久；
  3. 图检索 vs 向量检索：同一批记录，两种召回的差异与耗时；
  4. AI 关系边的“词面噪声”估计：两边正文几乎不共享词的比例（粗噪声代理）。

用法：
  python mvp.py --db <memore.db> --out report.md
"""
import argparse
import json
import os
import random
import sqlite3
import statistics
import sys
import time

random.seed(7)


def q(con, sql, args=()):
    return con.execute(sql, args).fetchall()


def ensure_proto_schema(con):
    con.execute("""CREATE TABLE IF NOT EXISTS events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        at INTEGER NOT NULL,
        kind TEXT NOT NULL,
        actor TEXT NOT NULL,
        payload TEXT NOT NULL
    )""")
    con.execute("CREATE INDEX IF NOT EXISTS idx_events_kind ON events(kind)")
    con.execute("CREATE INDEX IF NOT EXISTS idx_events_at ON events(at)")
    con.execute("""CREATE TABLE IF NOT EXISTS p_records (
        id TEXT PRIMARY KEY, content TEXT, type TEXT, created_at INTEGER,
        is_deleted INTEGER, correction_of_id TEXT, tags TEXT, analysis TEXT
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS p_relations (
        from_id TEXT, to_id TEXT, type TEXT, status TEXT,
        quote_a TEXT, quote_b TEXT, valid_from INTEGER, valid_to INTEGER,
        why TEXT, score REAL, PRIMARY KEY (from_id, to_id, type)
    )""")
    con.execute("""CREATE TABLE IF NOT EXISTS p_analysis (
        record_id TEXT PRIMARY KEY, summary TEXT, emotion TEXT, updated_at INTEGER
    )""")
    con.commit()


def clear_proto(con):
    for t in ("events", "p_records", "p_relations", "p_analysis"):
        con.execute(f"DELETE FROM {t}")
    con.commit()


def backfill_events(con):
    """把现有表转成事件流。只为 MVP：不追求事件粒度，够重放即可。"""
    t0 = time.time()
    n = 0
    records = q(con, "SELECT id, type, content, created_at, is_deleted, correction_of_id, emotional_tags, analysis FROM records")
    rows = []
    for rid, rtype, content, created, deleted, parent, tags, analysis in records:
        rows.append((created, "record.created" if not deleted else "record.deleted", "user",
                     json.dumps({"id": rid, "type": rtype, "content": content,
                                 "is_deleted": deleted, "correction_of_id": parent,
                                 "tags": tags}, ensure_ascii=False)))
        if analysis:
            rows.append((created + 1, "analysis.completed", "ai",
                         json.dumps({"id": rid, "analysis": analysis}, ensure_ascii=False)))
        n += 1
    rels = q(con, "SELECT from_id, to_id, type, status, created_at, quote_a, quote_b, why, score FROM record_relations")
    for f, t, typ, status, at, qa, qb, why, score in rels:
        rows.append((at, "relation.asserted", "ai" if typ == "LINK" else "user",
                     json.dumps({"from": f, "to": t, "type": typ, "status": status,
                                 "quote_a": qa or "", "quote_b": qb or "",
                                 "why": why or "", "score": score or 0.0}, ensure_ascii=False)))
    con.executemany("INSERT INTO events(at, kind, actor, payload) VALUES (?,?,?,?)", rows)
    con.commit()
    return {"records": n, "relations": len(rels), "events": len(rows),
            "seconds": round(time.time() - t0, 3)}


def replay(con):
    """从事件重放投影。等价于“规则改了以后重建全部读模型”。"""
    t0 = time.time()
    # records
    for (payload,) in q(con, "SELECT payload FROM events WHERE kind IN ('record.created','record.deleted') ORDER BY id"):
        d = json.loads(payload)
        con.execute("""INSERT OR REPLACE INTO p_records(id, content, type, created_at, is_deleted, correction_of_id, tags)
                       VALUES (?,?,?,?,?,?,?)""",
                    (d["id"], d["content"], d["type"], 0, d["is_deleted"], d["correction_of_id"], d["tags"]))
    for (payload,) in q(con, "SELECT payload FROM events WHERE kind = 'analysis.completed' ORDER BY id"):
        d = json.loads(payload)
        try:
            a = json.loads(d["analysis"]) if isinstance(d["analysis"], str) else d["analysis"]
        except Exception:
            a = {}
        con.execute("""INSERT OR REPLACE INTO p_analysis(record_id, summary, emotion, updated_at)
                       VALUES (?,?,?,?)""",
                    (d["id"], a.get("summary", ""), json.dumps(a.get("emotion", []), ensure_ascii=False), 0))
    for (payload,) in q(con, "SELECT payload FROM events WHERE kind = 'relation.asserted' ORDER BY id"):
        d = json.loads(payload)
        con.execute("""INSERT OR REPLACE INTO p_relations(from_id, to_id, type, status, quote_a, quote_b, valid_from, valid_to, why, score)
                       VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    (d["from"], d["to"], d["type"], d["status"], d.get("quote_a", ""), d.get("quote_b", ""),
                     0, None, d.get("why", ""), d.get("score", 0.0)))
    con.commit()
    return round(time.time() - t0, 3)


def char_bigrams(text):
    chars = [c for c in text if c.isalnum() or "\u4e00" <= c <= "\u9fff"]
    s = "".join(chars)
    return set(s[i:i + 2] for i in range(len(s) - 1))


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def load_embeddings(con):
    import struct
    vecs = {}
    for rid, vec, dim in q(con, "SELECT record_id, vec, dim FROM record_vectors"):
        if not vec or not dim:
            continue
        try:
            arr = struct.unpack(f"<{dim}f", vec[: 4 * dim])
        except Exception:
            continue
        norm = sum(x * x for x in arr) ** 0.5 or 1.0
        arr = [x / norm for x in arr]
        if rid in vecs:
            # 多条块取平均后再归一
            prev = vecs[rid]
            avg = [(a + b) / 2 for a, b in zip(prev, arr)]
            n2 = sum(x * x for x in avg) ** 0.5 or 1.0
            vecs[rid] = [x / n2 for x in avg]
        else:
            vecs[rid] = arr
    return vecs


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def build_graph(con):
    g = {}
    for f, t, score in q(con, "SELECT from_id, to_id, COALESCE(score,0) FROM p_relations WHERE type='LINK' AND status='ACTIVE'"):
        w = 1.0 + float(score)
        g.setdefault(f, {})[t] = g.setdefault(f, {}).get(t, 0) + w
        g.setdefault(t, {})[f] = g.setdefault(t, {}).get(f, 0) + w
    return g


def graph_topk(g, start, k=10, depth=2, decay=0.5):
    score = {}
    frontier = {start: 1.0}
    for _ in range(depth):
        nxt = {}
        for node, base in frontier.items():
            for nb, w in g.get(node, {}).items():
                if nb == start:
                    continue
                add = base * decay * w
                score[nb] = score.get(nb, 0.0) + add
                nxt[nb] = nxt.get(nb, 0.0) + add
        frontier = nxt
    return [n for n, _ in sorted(score.items(), key=lambda kv: kv[1], reverse=True)[:k]]


def vector_topk(vecs, start, k=10):
    v = vecs.get(start)
    if not v:
        return []
    sims = [(rid, cosine(v, other)) for rid, other in vecs.items() if rid != start]
    sims.sort(key=lambda kv: kv[1], reverse=True)
    return [rid for rid, _ in sims[:k]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    con = sqlite3.connect(args.db)
    ensure_proto_schema(con)
    clear_proto(con)

    report = {}
    report["backfill"] = backfill_events(con)
    report["replay_seconds"] = replay(con)

    # 一致性：投影 vs 旧表
    old_records = q(con, "SELECT COUNT(*) FROM records")[0][0]
    new_records = q(con, "SELECT COUNT(*) FROM p_records")[0][0]
    old_links = q(con, "SELECT COUNT(*) FROM record_relations WHERE type='LINK' AND status='ACTIVE'")[0][0]
    new_links = q(con, "SELECT COUNT(*) FROM p_relations WHERE type='LINK' AND status='ACTIVE'")[0][0]
    report["consistency"] = {"records_old": old_records, "records_projection": new_records,
                             "links_old": old_links, "links_projection": new_links}

    # 关系边的词面噪声代理：两端正文 bigram Jaccard
    texts = dict(q(con, "SELECT id, content FROM records"))
    overlaps = []
    for f, t in q(con, "SELECT from_id, to_id FROM p_relations WHERE type='LINK' AND status='ACTIVE'"):
        if f in texts and t in texts:
            overlaps.append(jaccard(char_bigrams(texts[f]), char_bigrams(texts[t])))
    if overlaps:
        report["link_lexical_overlap"] = {
            "n": len(overlaps),
            "median": round(statistics.median(overlaps), 4),
            "p90": round(sorted(overlaps)[int(len(overlaps) * 0.9)], 4),
            "share_below_0.01": round(sum(1 for x in overlaps if x < 0.01) / len(overlaps), 3),
        }

    # 图 vs 向量召回（在有向量的记录里随机抽 40 条）
    vecs = load_embeddings(con)
    g = build_graph(con)
    candidates = [rid for rid in vecs if rid in g]
    sample = random.sample(candidates, min(40, len(candidates)))

    t0 = time.time()
    graph_hits = {rid: graph_topk(g, rid) for rid in sample}
    graph_ms = round((time.time() - t0) * 1000 / max(1, len(sample)), 2)

    t0 = time.time()
    vector_hits = {rid: vector_topk(vecs, rid) for rid in sample}
    vector_ms = round((time.time() - t0) * 1000 / max(1, len(sample)), 2)

    jac = []
    for rid in sample:
        a, b = set(graph_hits[rid]), set(vector_hits[rid])
        if a or b:
            jac.append(len(a & b) / len(a | b))
    report["retrieval"] = {
        "sample": len(sample),
        "graph_top10_ms_per_query": graph_ms,
        "vector_top10_ms_per_query": vector_ms,
        "avg_jaccard_graph_vs_vector": round(sum(jac) / len(jac), 3) if jac else None,
    }

    # 覆盖：图上找到、向量 top10 里没有的占比（说明图给了新东西）
    novel = 0
    total = 0
    for rid in sample:
        vs = set(vector_hits[rid])
        for h in graph_hits[rid]:
            total += 1
            if h not in vs:
                novel += 1
    report["retrieval"]["graph_novel_share"] = round(novel / total, 3) if total else None

    lines = ["# Mirror 新架构 MVP 结果", ""]
    lines.append(f"- 事件回填：{report['backfill']['events']} 条事件，{report['backfill']['seconds']}s")
    lines.append(f"- 投影重建：{report['replay_seconds']}s")
    lines.append(f"- 一致性：记录 {new_records}/{old_records}，LINK 边 {new_links}/{old_links}")
    if "link_lexical_overlap" in report:
        o = report["link_lexical_overlap"]
        lines.append(f"- 现有 AI 边的词面重合：中位 {o['median']}，P90 {o['p90']}，"
                     f"低于 0.01 的占 {o['share_below_0.01']:.0%}（越低越可能是硬连）")
    r = report["retrieval"]
    lines.append(f"- 检索耗时：图 {r['graph_top10_ms_per_query']}ms/条，向量 {r['vector_top10_ms_per_query']}ms/条（样本 {r['sample']}）")
    lines.append(f"- 两种召回 top10 重合度：Jaccard {r['avg_jaccard_graph_vs_vector']}")
    lines.append(f"- 图召回里向量没给到的比例：{r['graph_novel_share']}")
    out = "\n".join(lines)
    print(out)
    if args.out:
        open(args.out, "w", encoding="utf-8").write(out + "\n")
    con.close()


if __name__ == "__main__":
    main()
