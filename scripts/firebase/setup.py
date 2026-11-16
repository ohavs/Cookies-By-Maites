"""Firebase setup for sign-in + cloud sync (idempotent). Run in CI with FIREBASE_TOKEN.

- enables the APIs (Identity Toolkit, Storage)
- registers the release keystore SHA-1/SHA-256 on the Android app (Google sign-in needs it)
- creates the default Storage bucket
- reads the Google sign-in web client id

Env in: FIREBASE_TOKEN, GOOGLE_SERVICES_JSON, SHA1, SHA256 (optional).
Writes GOOGLE_WEB_CLIENT_ID / STORAGE_BUCKET to $GITHUB_ENV when found. --read-only skips changes.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

READ_ONLY = "--read-only" in sys.argv
gs = json.loads(os.environ["GOOGLE_SERVICES_JSON"])
PROJECT = gs["project_info"]["project_id"]
APP_ID = next(c["client_info"]["mobilesdk_app_id"] for c in gs["client"]
              if c["client_info"]["android_client_info"]["package_name"] == "com.maites.cookies")


def access_token():
    body = urllib.parse.urlencode({
        # Public OAuth client of firebase-tools (same one `firebase login:ci` issues tokens for).
        "client_id": "563584335869-fgrhgmd47bqnekij5i8b5pr03ho849e6.apps.googleusercontent.com",
        "client_secret": "j9iVZfS8kkCEFUPaAeJV0sAi",
        "refresh_token": os.environ["FIREBASE_TOKEN"], "grant_type": "refresh_token",
    }).encode()
    with urllib.request.urlopen("https://oauth2.googleapis.com/token", body, timeout=30) as r:
        return json.load(r)["access_token"]


TOKEN = access_token()


def req(method, url, body=None):
    r = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None, headers={
        "Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json", "x-goog-user-project": PROJECT})
    try:
        with urllib.request.urlopen(r, timeout=60) as res:
            return res.status, json.loads(res.read() or b"{}")
    except urllib.error.HTTPError as e:
        txt = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(txt)
        except ValueError:
            return e.code, {"raw": txt[:300]}


def out(name, value):
    print(f"{name}={value}")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as f:
            f.write(f"{name}={value}\n")


def enable(api):
    url = f"https://serviceusage.googleapis.com/v1/projects/{PROJECT}/services/{api}"
    code, d = req("GET", url)
    state = d.get("state")
    print(f"api {api}: {state or code}")
    if state != "ENABLED" and not READ_ONLY:
        code, d = req("POST", url + ":enable", {})
        print("  enable:", code, json.dumps(d)[:200])
        for _ in range(20):
            time.sleep(6)
            if req("GET", url)[1].get("state") == "ENABLED":
                print("  enabled")
                return


def shas():
    want = {("SHA_1", os.environ.get("SHA1", "")), ("SHA_256", os.environ.get("SHA256", ""))}
    want = {(t, h.replace(":", "").lower()) for t, h in want if h}
    base = f"https://firebase.googleapis.com/v1beta1/projects/{PROJECT}/androidApps/{APP_ID}/sha"
    code, d = req("GET", base)
    have = {(c.get("certType"), c.get("shaHash", "").lower()) for c in d.get("certificates", [])}
    print(f"sha certs on app: {len(have)} (status {code})")
    for t, h in sorted(want - have):
        code, d = req("POST", base, {"shaHash": h, "certType": t})
        print(f"  add {t}: {code} {json.dumps(d)[:200]}")


def bucket():
    base = f"https://firebasestorage.googleapis.com/v1beta/projects/{PROJECT}/defaultBucket"
    code, d = req("GET", base)
    if code == 404 and not READ_ONLY:
        # US regions keep Storage inside the no-cost quota.
        code, d = req("POST", base, {"location": "US-EAST1"})
        print("create bucket:", code, json.dumps(d)[:300])
    name = (d.get("bucket") or {}).get("name") or d.get("name", "")
    name = name.rsplit("/", 1)[-1] if name else gs["project_info"].get("storage_bucket", "")
    print("bucket:", code, name)
    if name:
        out("STORAGE_BUCKET", name)


def google_client():
    code, d = req("GET", f"https://identitytoolkit.googleapis.com/admin/v2/projects/{PROJECT}/defaultSupportedIdpConfigs/google.com")
    if code == 200 and d.get("enabled") and d.get("clientId"):
        out("GOOGLE_WEB_CLIENT_ID", d["clientId"])
    else:
        print(f"::warning::Google sign-in provider is not enabled in Firebase Auth ({code} {json.dumps(d)[:200]})")


for api in ["identitytoolkit.googleapis.com", "firebasestorage.googleapis.com", "storage.googleapis.com"]:
    enable(api)
if not READ_ONLY:
    shas()
bucket()
google_client()
