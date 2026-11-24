#!/usr/bin/env python3
"""
批量分析质量检查（2026-11-20）

用法：
  1. 手机上做完批量分析后，把库和运行日志拉到电脑：
       adb -s <serial> shell "run-as app.memore cat databases/memore.db > /data/local/tmp/q.db"
       adb -s <serial> shell "run-as app.memore cat databases/memore.db-wal > /data/local/tmp/q.db-wal"
       adb -s <serial> shell "run-as app.memore cat files/ai_runs.jsonl > /data/local/tmp/q_runs.jsonl"
       adb -s <serial> pull /data/local/tmp/q.db q.db
       adb -s <serial> pull /data/local/tmp/q.db-wal q.db-wal
       adb -s <serial> pull /data/local/tmp/q_runs.jsonl q_runs.jsonl
  2. python batch_quality.py --db q.db --runs q_runs.jsonl --since "<分析开始时间 ISO，可省>"

只读，不改任何数据。
"""
import argparse
import collections
import datetime
import json
import os
import sqlite3
import statistics
import io
import sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--runs", default="")
    ap.add_argument("--since", default="", help="只统计这个时间之后的分析版本（ISO，如 2026-11-20T00:30）")
    ap.add_argument("--limit", type=int, default=8, help="逐条抽样显示几条")
    return ap.parse_args()


def main():
    args = parse_args()
    con = sqlite3.connect(args.db)
    con.row_factory = sqlite3.Row
    since_ms = 0
    if args.since:
        since_ms = int(datetime.datetime.fromisoformat(args.since).timestamp() * 1000)

    # 1) 分析版本
    versions = con.execute(
        "SELECT * FROM analysis_versions WHERE created_at >= ? ORDER BY id DESC", (since_ms,)
    ).fetchall()
    print("== 分析版本 ==")
    print("条数:", len(versions))
    by_status = collections.Counter(v["status"] for v in versions)
    by_prompt = collections.Counter(v["prompt_version"] for v in versions)
    print("状态:", dict(by_status), "| 提示词版本:", dict(by_prompt))
    failed = [v for v in versions if v["status"] != "OK"]
    for v in failed[:10]:
        print("  失败:", v["record_id"][:8], v["reason"][:60])
    ok = [v for v in versions if v["status"] == "OK" and v["summary"]]
    if ok:
        lens = [len(v["summary"]) for v in ok]
        print("总结长度: 中位", int(statistics.median(lens)), "最短", min(lens), "最长", max(lens))
        empty = sum(1 for v in versions if v["status"] == "OK" and not (v["summary"] or "").strip())
        print("总结为空:", empty)

    # 2) 锚点
    print("\n== 锚点 ==")
    anchors = con.execute("SELECT * FROM record_entities").fetchall()
    kinds = collections.Counter(a["kind"] for a in anchors)
    print("总数:", len(anchors), "| 类型:", dict(kinds))
    per = collections.Counter(a["record_id"] for a in anchors)
    if per:
        print("每记录锚点数: 中位", int(statistics.median(list(per.values()))),
              "最多", max(per.values()), "覆盖记录", len(per))
    for a in anchors[:args.limit]:
        print(f"  {a['name']}({a['kind']})  «{a['quote'][:28]}»")

    # 3) 关系边
    print("\n== 关系边 ==")
    rels = con.execute("SELECT * FROM record_relations").fetchall()
    live = [r for r in rels if r["valid_to"] is None]
    ai_live = [r for r in live if r["source"] == "AI"]
    exp = [r for r in rels if r["valid_to"] is not None]
    print("有效边:", len(live), "| 其中 AI:", len(ai_live), "| 已失效:", len(exp))
    types = collections.Counter(r["type"] for r in ai_live)
    print("AI 边类型:", dict(types))
    quoted = sum(1 for r in ai_live if (r["quote_a"] or "").strip() and (r["quote_b"] or "").strip())
    print("AI 边带双向引文:", quoted, "/", len(ai_live))

    # 4) 运行日志（可选）
    if args.runs and os.path.exists(args.runs):
        print("\n== AI 运行日志 ==")
        runs = []
        for line in open(args.runs, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                runs.append(json.loads(line))
            except Exception:
                pass
        runs = [r for r in runs if (r.get("at", 0) >= since_ms)]
        if runs:
            ok_n = sum(1 for r in runs if r.get("ok"))
            print("调用:", len(runs), "成功:", ok_n, "失败:", len(runs) - ok_n)
            durs = [r.get("durationMs", 0) for r in runs if r.get("durationMs")]
            if durs:
                print("耗时: 中位 %.1fs 最长 %.1fs" % (statistics.median(durs) / 1000, max(durs) / 1000))
            dropped = [r.get("droppedLinks", 0) for r in runs if r.get("droppedLinks")]
            if dropped:
                print("被丢弃的关联总数:", sum(dropped))
            errors = [r for r in runs if not r.get("ok")]
            for e in errors[:5]:
                print("  失败:", e.get("recordId", "")[:8], (e.get("error") or "")[:60])

    # 5) 逐条抽样（最新几条 OK 的）
    print("\n== 逐条抽样 ==")
    for v in ok[:args.limit]:
        rid = v["record_id"]
        rec = con.execute("SELECT substr(content,1,50) c FROM records WHERE id=?", (rid,)).fetchone()
        a = con.execute("SELECT name, kind FROM record_entities WHERE record_id=?", (rid,)).fetchall()
        e = con.execute(
            "SELECT type, substr(why,1,20) w FROM record_relations WHERE from_id=? AND valid_to IS NULL", (rid,)
        ).fetchall()
        print(f"\n- {rid[:8]} «{(rec['c'] if rec else '').replace(chr(10), ' ')}…»")
        print("  锚点:", ", ".join(f"{x['name']}/{x['kind']}" for x in a) or "无")
        print("  出边:", ", ".join(f"{x['type']}:{x['w']}" for x in e) or "无")

    con.close()


if __name__ == "__main__":
    main()
