"""Read-only look at shared accounts: invites, and per account the newest docs by syncAt. Emails/uids masked."""
import json, os, sys, urllib.parse
sys.argv.append("--read-only")
sys.path.insert(0, os.path.dirname(__file__))
os.environ.pop("GITHUB_ENV", None)
import importlib.util
spec = importlib.util.spec_from_file_location("s", os.path.join(os.path.dirname(__file__), "setup.py"))
src = open(spec.origin).read().split("\nfor api in [")[0]  # helpers only, no setup actions
g = {"__file__": spec.origin, "__name__": "s"}
exec(compile(src, spec.origin, "exec"), g)
req, PROJECT = g["req"], g["PROJECT"]
DOCS = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"
m = lambda s: (s[:4] + "…") if s else "-"

def val(v):
    if not v: return None
    return next(iter(v.values()))

code, d = req("GET", f"{DOCS}/invites?pageSize=100")
print("invites", code)
owners = set()
for doc in d.get("documents", []):
    f = doc.get("fields", {})
    owners.add(val(f.get("owner")))
    print(" ", m(doc["name"].split("/")[-1]), "owner", m(val(f.get("owner"))), "role", val(f.get("role")), "joined", val(f.get("joined")), "at", doc.get("updateTime"))

code, d = req("GET", f"{DOCS}/users?pageSize=300&showMissing=true&mask.fieldPaths=x")
uids = {doc["name"].split("/")[-1] for doc in d.get("documents", [])} | owners
print("users", code, len(uids))
for uid in sorted(u for u in uids if u):
    print("== user", m(uid), "(owner of invite)" if uid in owners else "")
    for kind in ["recipes", "products", "categories", "shop"]:
        q = {"structuredQuery": {"from": [{"collectionId": kind}], "orderBy": [{"field": {"fieldPath": "syncAt"}, "direction": "DESCENDING"}], "limit": 4}}
        code, r = req("POST", f"{DOCS}/users/{uid}:runQuery", q)
        rows = [x["document"] for x in r if isinstance(x, dict) and "document" in x] if isinstance(r, list) else []
        cq = {"structuredAggregationQuery": {"structuredQuery": {"from": [{"collectionId": kind}]}, "aggregations": [{"alias": "n", "count": {}}]}}
        c2, cr = req("POST", f"{DOCS}/users/{uid}:runAggregationQuery", cq)
        n = val(cr[0]["result"]["aggregateFields"]["n"]) if c2 == 200 and isinstance(cr, list) else f"? {c2}"
        print(f"  {kind}: {n} (q {code})")
        for x in rows:
            f = x.get("fields", {})
            print("    ", (val(f.get("name")) or val(f.get("label")) or "")[:24], "| syncAt", val(f.get("syncAt")), "| deleted" if val(f.get("deleted")) else "")
