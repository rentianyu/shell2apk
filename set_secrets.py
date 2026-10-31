#!/usr/bin/env python3
"""设置 GitHub Actions secrets（libsodium sealed box）"""
import base64
import json
import os
import sys
import urllib.request

from nacl import encoding, public

REPO = "rentianyu/shell2apk"
TOKEN = os.environ["GH_TOKEN"]
API = "https://api.github.com"


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "s2a-setup",
    })
    with urllib.request.urlopen(r, timeout=60) as resp:
        raw = resp.read().decode()
        return json.loads(raw) if raw else {}


def set_secret(name, value):
    pk = req("GET", f"/repos/{REPO}/actions/secrets/public-key")
    sealed = public.SealedBox(public.PublicKey(pk["key"].encode(), encoding.Base64Encoder()))
    enc = base64.b64encode(sealed.encrypt(value.encode())).decode()
    req("PUT", f"/repos/{REPO}/actions/secrets/{name}", {"encrypted_value": enc, "key_id": pk["key_id"]})
    print(f"  set {name} ({len(value)} chars)")


def main():
    ks_path = "/sandbox/workspace/shell2apk/cred/keystore.p12"
    pwd = open("/sandbox/workspace/shell2apk/cred/ks_pwd.txt").read().strip()
    b64 = base64.b64encode(open(ks_path, "rb").read()).decode()
    set_secret("KEYSTORE_BASE64", b64)
    set_secret("KEYSTORE_PASSWORD", pwd)
    set_secret("KEY_ALIAS", "shell2apk")
    lst = req("GET", f"/repos/{REPO}/actions/secrets")
    print("现有 secrets:", sorted(s["name"] for s in lst.get("secrets", [])))


if __name__ == "__main__":
    main()
