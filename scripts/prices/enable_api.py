"""Enable the Cloud Firestore API on the project (idempotent)."""
import json
import os
import time
import urllib.request

from fetch import Firestore

fs = Firestore.__new__(Firestore)
Firestore.__init__(fs, os.environ["FIREBASE_PROJECT"], os.environ["FIREBASE_TOKEN"])
url = f"https://serviceusage.googleapis.com/v1/projects/{os.environ['FIREBASE_PROJECT']}/services/firestore.googleapis.com"
state = fs._req("GET", url).get("state")
print("firestore api:", state)
if state != "ENABLED":
    fs._req("POST", url + ":enable", {})
    for _ in range(30):
        time.sleep(10)
        if fs._req("GET", url).get("state") == "ENABLED":
            print("enabled; waiting for propagation")
            time.sleep(60)
            break
