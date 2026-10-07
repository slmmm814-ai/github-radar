"""اختبار ذاتي ببيانات اصطناعية: يولّد ملفات GH Archive مزيفة، يشغّلها عبر خادم محلي، ثم يختبر الـ API."""
import functools
import gzip
import json
import os
import socket
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


tmp = tempfile.mkdtemp()
arch = os.path.join(tmp, "arch")
os.makedirs(arch)
src_port, api_port = free_port(), free_port()

os.environ.update({
    "GHARCHIVE_BASE": f"http://127.0.0.1:{src_port}",
    "RADAR_DB": os.path.join(tmp, "t.db"),
    "RADAR_KEY": "secret",
    "RADAR_BACKFILL_HOURS": "48",
    "RADAR_LAG_HOURS": "2",
    "RADAR_MIN_HOURLY": "3",
})
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import radar_server as rs  # noqa: E402

last = rs.floor_hour(time.time()) - 2 * 3600
start = last - 47 * 3600


def ev(kind, repo):
    return json.dumps({"id": "1", "type": kind, "repo": {"id": 1, "name": repo}, "payload": {}})


# الساعة الأخيرة (i=47) غير منشورة عمدًا لاختبار 404
for i in range(0, 47):
    h = start + i * 3600
    lines = []
    lines += [ev("WatchEvent", "acme/surge")] * (50 if i >= 23 else 0)
    lines += [ev("WatchEvent", "acme/steady")] * 10
    lines += [ev("WatchEvent", "acme/tiny")] * 2            # تحت العتبة
    lines += [ev("ForkEvent", "acme/forker")] * 5            # تفرّعات فقط
    lines += [ev("PushEvent", "acme/pushy")] * 40            # يُهمل
    lines.append(json.dumps({"type": "PushEvent", "repo": {"name": "x/y"},
                             "payload": {"commits": [{"message": "fix WatchEvent bug"}]}}))  # إيجابي كاذب
    lines.append("{ not json WatchEvent")                    # سطر تالف
    dt = datetime.fromtimestamp(h, timezone.utc)
    with gzip.open(os.path.join(arch, f"{dt:%Y-%m-%d}-{dt.hour}.json.gz"), "wt") as f:
        f.write("\n".join(lines) + "\n")

handler = functools.partial(SimpleHTTPRequestHandler, directory=arch)
handler.log_message = lambda *a, **k: None
src = ThreadingHTTPServer(("127.0.0.1", src_port), SimpleHTTPRequestHandler.__class__ and handler)
threading.Thread(target=src.serve_forever, daemon=True).start()

rs.init_db()
new = rs.ingest_missing()
assert new == 47, f"expected 47 new hours, got {new}"
assert rs.ingest_missing() == 0, "second run must be idempotent"

api = rs.make_server(api_port)
threading.Thread(target=api.serve_forever, daemon=True).start()


def get(path, key=True):
    req = urllib.request.Request(f"http://127.0.0.1:{api_port}{path}", headers={"X-Api-Key": "secret"} if key else {})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


assert get("/health", key=False)[0] == 200
assert get("/trending", key=False)[0] == 401
code, t = get("/trending?window=24&limit=10")
assert code == 200 and t["prev_ok"], t
repos = [i["repo"] for i in t["items"]]
assert repos[0] == "acme/surge", repos
sur = t["items"][0]
assert sur["stars"] == 1200 and sur["prev_stars"] == 0 and sur["accel"] == 50.0, sur
steady = next(i for i in t["items"] if i["repo"] == "acme/steady")
assert steady["stars"] == 240 and steady["accel"] < 1.2, steady
for bad in ("acme/tiny", "acme/forker", "acme/pushy", "x/y"):
    assert bad not in repos, bad
code, r = get("/rising")
assert [i["repo"] for i in r["items"]] == ["acme/surge"], r
code, s = get("/trending?sort=accel&limit=1")
assert s["items"][0]["repo"] == "acme/surge"
code, series = get("/repo/ACME/surge?hours=48")
assert code == 200 and len(series["points"]) == 24 and all(p["stars"] == 50 for p in series["points"]), series
assert get("/repo/onlyone")[0] == 400
assert get("/nope")[0] == 404
out = os.path.join(tmp, "out", "trending.json")
rs.export(out)
with open(out, encoding="utf-8") as f:
    j = json.load(f)
assert j["items"][0]["repo"] == "acme/surge" and j["items"][0]["accel"] == 50.0, j
print("OK — جميع الاختبارات نجحت")
