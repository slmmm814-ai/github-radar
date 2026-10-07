#!/usr/bin/env python3
"""
Radar Server — يجمع نجوم وتفرّعات GitHub من GH Archive ويقدّمها عبر API بسيط لتطبيق رادار GitHub+.
بلا أي مكتبات خارجية (Python 3.9+ فقط).

التشغيل:      python3 radar_server.py
مرة واحدة:    python3 radar_server.py --once
تصدير JSON:   python3 radar_server.py --export out/trending.json   (يجلب ثم يكتب الملف ويخرج)
"""
import gzip
import hmac
import json
import math
import os
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE = os.environ.get("GHARCHIVE_BASE", "https://data.gharchive.org").rstrip("/")
DB_PATH = os.environ.get("RADAR_DB", "radar.db")
API_KEY = os.environ.get("RADAR_KEY", "")
BACKFILL = int(os.environ.get("RADAR_BACKFILL_HOURS", "48"))        # ساعات الجلب عند أول تشغيل
RETENTION_DAYS = int(os.environ.get("RADAR_RETENTION_DAYS", "14"))  # مدة الاحتفاظ
LAG = int(os.environ.get("RADAR_LAG_HOURS", "2"))                   # تأخير نشر GH Archive للملف
MIN_HOURLY = int(os.environ.get("RADAR_MIN_HOURLY", "3"))           # أقل نجوم/تفرّعات في الساعة ليُخزَّن المستودع
PORT = int(os.environ.get("PORT", "8787"))
INTERVAL = int(os.environ.get("RADAR_INTERVAL_SECONDS", "900"))     # فاصل فحص الملفات الجديدة

SCHEMA = """
CREATE TABLE IF NOT EXISTS hourly(
  hour INTEGER NOT NULL, repo TEXT NOT NULL, stars INTEGER NOT NULL, forks INTEGER NOT NULL,
  PRIMARY KEY(hour, repo)) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_hourly_repo ON hourly(repo);
CREATE TABLE IF NOT EXISTS ingested(hour INTEGER PRIMARY KEY, rows INTEGER NOT NULL, at INTEGER NOT NULL);
"""


def log(*a):
    print(datetime.now(timezone.utc).strftime("%H:%M:%S"), *a, flush=True)


def init_db():
    c = sqlite3.connect(DB_PATH, timeout=30)
    c.execute("PRAGMA journal_mode=WAL")
    c.executescript(SCHEMA)
    c.close()


def db():
    c = sqlite3.connect(DB_PATH, timeout=30)
    c.execute("PRAGMA journal_mode=WAL")
    return c


def floor_hour(ts):
    return int(ts) // 3600 * 3600


def iso(h):
    return datetime.fromtimestamp(h, timezone.utc).strftime("%Y-%m-%dT%H:00:00Z")


def hour_url(h):
    dt = datetime.fromtimestamp(h, timezone.utc)
    return f"{BASE}/{dt:%Y-%m-%d}-{dt.hour}.json.gz"


# ------------------------------------------------------------------ الجلب

def ingest_hour(conn, h):
    """ينزّل ملف ساعة واحدة ويعدّ النجوم والتفرّعات لكل مستودع. يعيد عدد الصفوف أو None إن لم يُنشر الملف بعد."""
    req = urllib.request.Request(hour_url(h), headers={"User-Agent": "radar-server/1.0"})
    try:
        resp = urllib.request.urlopen(req, timeout=120)
    except urllib.error.HTTPError as e:
        if e.code in (403, 404):
            return None
        raise
    stars, forks = {}, {}
    with resp:
        with gzip.GzipFile(fileobj=resp) as gz:
            for line in gz:
                # فحص رخيص قبل التحليل؛ ثم نتحقق من النوع بعد json.loads لتفادي الإيجابيات الكاذبة
                if b"WatchEvent" in line:
                    kind, target = "WatchEvent", stars
                elif b"ForkEvent" in line:
                    kind, target = "ForkEvent", forks
                else:
                    continue
                try:
                    ev = json.loads(line)
                    if ev.get("type") != kind:
                        continue
                    name = ev["repo"]["name"]
                except (ValueError, KeyError, TypeError):
                    continue
                target[name] = target.get(name, 0) + 1
    rows = []
    for n in set(stars) | set(forks):
        s, f = stars.get(n, 0), forks.get(n, 0)
        if s >= MIN_HOURLY or f >= MIN_HOURLY:
            rows.append((h, n, s, f))
    with conn:
        conn.execute("DELETE FROM hourly WHERE hour=?", (h,))
        conn.executemany("INSERT INTO hourly VALUES (?,?,?,?)", rows)
        conn.execute("INSERT OR REPLACE INTO ingested VALUES (?,?,?)", (h, len(rows), int(time.time())))
    return len(rows)


def ingest_missing():
    conn = db()
    last = floor_hour(time.time()) - LAG * 3600
    start = last - (BACKFILL - 1) * 3600
    done = {r[0] for r in conn.execute("SELECT hour FROM ingested WHERE hour>=?", (start,))}
    new = 0
    for h in range(start, last + 1, 3600):
        if h in done:
            continue
        t0 = time.time()
        try:
            n = ingest_hour(conn, h)
        except Exception as e:  # شبكة أو ملف تالف: نكمل ونعيد المحاولة في الدورة القادمة
            log("خطأ في جلب", iso(h), repr(e))
            continue
        if n is None:
            log("غير متاح بعد:", iso(h))
            continue
        new += 1
        log(f"تم {iso(h)}: {n} مستودعًا خلال {time.time() - t0:.0f} ث")
    cutoff = floor_hour(time.time()) - RETENTION_DAYS * 86400
    with conn:
        conn.execute("DELETE FROM hourly WHERE hour<?", (cutoff,))
        conn.execute("DELETE FROM ingested WHERE hour<?", (cutoff,))
    conn.close()
    return new


def ingest_loop():
    while True:
        try:
            ingest_missing()
        except Exception as e:
            log("خطأ في دورة الجلب:", repr(e))
        time.sleep(INTERVAL)


# ------------------------------------------------------------------ الاستعلامات

def anchor(conn):
    return conn.execute("SELECT MAX(hour) FROM ingested").fetchone()[0]


def trending(conn, window=24, limit=30, min_stars=10, sort="heat"):
    """نجوم آخر `window` ساعة مقابل النافذة السابقة لها.
    accel = نجوم النافذة الحالية / max(نجوم السابقة, window)، فالأرضية تعوّض أن الساعات تحت العتبة غير مخزَّنة."""
    a = anchor(conn)
    if a is None:
        return {"anchor": None, "items": []}
    cf = a - (window - 1) * 3600
    pf = cf - window * 3600
    cov_cur = conn.execute("SELECT COUNT(*) FROM ingested WHERE hour>=? AND hour<=?", (cf, a)).fetchone()[0]
    cov_prev = conn.execute("SELECT COUNT(*) FROM ingested WHERE hour>=? AND hour<?", (pf, cf)).fetchone()[0]
    prev_ok = cov_prev >= 0.8 * window
    rows = conn.execute(
        """SELECT repo,
                  SUM(CASE WHEN hour>=:cf THEN stars ELSE 0 END) AS cs,
                  SUM(CASE WHEN hour>=:cf THEN forks ELSE 0 END) AS cfk,
                  SUM(CASE WHEN hour<:cf THEN stars ELSE 0 END) AS ps
           FROM hourly WHERE hour>=:pf AND hour<=:a
           GROUP BY repo HAVING cs>=:m""",
        {"cf": cf, "pf": pf, "a": a, "m": min_stars},
    ).fetchall()
    items = []
    for repo, cs, cfk, ps in rows:
        accel = round(cs / max(ps, window), 2) if prev_ok else None
        heat = cs * (1 + math.log(min(accel, 50))) if accel and accel > 1 else cs
        items.append({
            "repo": repo, "stars": cs, "forks": cfk,
            "prev_stars": ps if prev_ok else None,
            "accel": accel, "heat": round(heat, 1),
        })
    keys = {
        "stars": lambda x: x["stars"],
        "forks": lambda x: x["forks"],
        "accel": lambda x: (x["accel"] or 0, x["stars"]),
        "heat": lambda x: x["heat"],
    }
    items.sort(key=keys.get(sort, keys["heat"]), reverse=True)
    return {
        "anchor": iso(a), "window_hours": window,
        "coverage": {"current": cov_cur, "previous": cov_prev}, "prev_ok": prev_ok,
        "items": items[:limit],
    }


def repo_series(conn, repo, hours):
    a = anchor(conn)
    if a is None:
        return {"repo": repo, "points": []}
    rows = conn.execute(
        "SELECT hour, stars, forks FROM hourly WHERE repo=? COLLATE NOCASE AND hour>=? ORDER BY hour",
        (repo, a - (hours - 1) * 3600),
    ).fetchall()
    return {
        "repo": repo, "anchor": iso(a), "min_hourly": MIN_HOURLY,
        "note": "الساعات تحت العتبة غير مخزَّنة وتظهر غائبة",
        "points": [{"t": iso(h), "stars": s, "forks": f} for h, s, f in rows],
    }


def health():
    conn = db()
    a = anchor(conn)
    out = {
        "ok": True,
        "latest_hour": iso(a) if a else None,
        "hours_ingested": conn.execute("SELECT COUNT(*) FROM ingested").fetchone()[0],
        "rows": conn.execute("SELECT COUNT(*) FROM hourly").fetchone()[0],
        "min_hourly": MIN_HOURLY,
    }
    conn.close()
    return out


# ------------------------------------------------------------------ HTTP

def num(q, key, default, lo, hi):
    try:
        v = int(q.get(key, default))
    except (TypeError, ValueError):
        v = default
    return max(lo, min(hi, v))


class Handler(BaseHTTPRequestHandler):
    server_version = "RadarServer/1.0"

    def log_message(self, *args):
        pass

    def send_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self, q):
        if not API_KEY:
            return True
        given = self.headers.get("X-Api-Key") or q.get("key") or ""
        return hmac.compare_digest(given, API_KEY)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        path = u.path.rstrip("/") or "/"
        try:
            if path == "/health":
                return self.send_json(200, health())
            if not self.authorized(q):
                return self.send_json(401, {"error": "unauthorized"})
            conn = db()
            try:
                if path in ("/trending", "/rising"):
                    window = num(q, "window", 24, 1, 168)
                    limit = num(q, "limit", 30, 1, 100)
                    min_stars = num(q, "min_stars", 20 if path == "/rising" else 10, 1, 100000)
                    sort = q.get("sort", "accel" if path == "/rising" else "heat")
                    res = trending(conn, window, limit if path == "/trending" else 100, min_stars, sort)
                    if path == "/rising":
                        floor = num(q, "min_accel", 3, 1, 1000)
                        res["items"] = [i for i in res["items"] if i["accel"] and i["accel"] >= floor][:limit]
                    return self.send_json(200, res)
                if path.startswith("/repo/"):
                    parts = path[len("/repo/"):].split("/")
                    if len(parts) != 2 or not all(parts):
                        return self.send_json(400, {"error": "use /repo/<owner>/<name>"})
                    return self.send_json(200, repo_series(conn, "/".join(parts), num(q, "hours", 72, 1, 336)))
                return self.send_json(404, {"error": "not found"})
            finally:
                conn.close()
        except Exception as e:
            log("خطأ في الطلب", self.path, repr(e))
            return self.send_json(500, {"error": "server error"})


def export(path, window=24, limit=50, min_stars=10):
    """يكتب نتيجة /trending في ملف JSON ثابت (للنشر عبر GitHub Actions بلا خادم)."""
    conn = db()
    res = trending(conn, window, limit, min_stars, "heat")
    conn.close()
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(res, f, ensure_ascii=False)
    return res


def make_server(port):
    return ThreadingHTTPServer(("0.0.0.0", port), Handler)


def main():
    init_db()
    if "--export" in sys.argv:
        out = sys.argv[sys.argv.index("--export") + 1]
        ingest_missing()
        res = export(out)
        log(f"تم تصدير {len(res['items'])} مستودعًا إلى {out}")
        return
    if "--once" in sys.argv:
        log("تم جلب", ingest_missing(), "ساعة جديدة")
        return
    threading.Thread(target=ingest_loop, daemon=True).start()
    if not API_KEY:
        log("تحذير: RADAR_KEY غير مضبوط، الـ API مفتوح لكل من يصل إلى المنفذ.")
    log(f"الخادم يعمل على المنفذ {PORT} | المصدر: {BASE} | قاعدة البيانات: {DB_PATH}")
    make_server(PORT).serve_forever()


if __name__ == "__main__":
    main()
