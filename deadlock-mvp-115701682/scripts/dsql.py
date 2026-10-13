#!/usr/bin/env python3
"""Query api.deadlock-api.com /v1/sql, honouring the 2 req/min rate limit. Usage: dsql.py 'SQL' [outfile]"""
import sys, time, json, urllib.request, urllib.parse, urllib.error
q = sys.argv[1]; out = sys.argv[2] if len(sys.argv) > 2 else None
url = "https://api.deadlock-api.com/v1/sql?" + urllib.parse.urlencode({"query": q})
for attempt in range(15):
    try:
        body = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "curl/8.5.0"}), timeout=300).read().decode()
        break
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        if e.code == 429:
            wait = json.loads(body).get("error", {}).get("next_request_in", 30) + 2
            print(f"[429, waiting {wait}s]", file=sys.stderr); time.sleep(wait); continue
        print(f"HTTP {e.code}: {body}", file=sys.stderr); sys.exit(1)
if out: open(out, "w").write(body); print(f"wrote {out} ({len(body)} bytes)")
else: print(body)
