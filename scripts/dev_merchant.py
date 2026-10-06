"""Create a local dev merchant with a TEST API key and the sandbox provider credentials, and save
the key to .env as GATEWAY_DEV_API_KEY. Reads everything from .env; prints nothing secret.

    python scripts/dev_merchant.py

Idempotent on the .env side: if GATEWAY_DEV_API_KEY is already set, it exits without creating
another merchant (a key is shown once by the API, so the saved one is the only copy).
"""

import json
import os
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_PATH = os.path.join(ROOT, ".env")
GATEWAY = os.environ.get("GATEWAY", "http://localhost:8080")


def read_env():
    env = {}
    with open(ENV_PATH, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.split("#", 1)[0].strip().strip('"').strip("'")
    return env


def call(method, path, body, admin_key, expect):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(GATEWAY + path, data=data, method=method)
    request.add_header("X-Admin-Key", admin_key)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read()
    if status not in expect:
        # Only the status and the problem type: the body of an admin call can carry names.
        problem = {}
        try:
            problem = json.loads(raw or b"{}")
        except ValueError:
            pass
        sys.exit(f"{method} {path} -> {status} {problem.get('type', '')}")
    return json.loads(raw) if raw else {}


def main():
    env = read_env()
    if env.get("GATEWAY_DEV_API_KEY"):
        print("GATEWAY_DEV_API_KEY already set in .env; nothing to do")
        return
    admin_key = env.get("GATEWAY_ADMIN_KEY")
    if not admin_key:
        sys.exit("GATEWAY_ADMIN_KEY missing in .env")

    merchant = call("POST", "/v1/admin/merchants", {"name": "Loja de Dev"}, admin_key, {201})
    merchant_id = merchant["id"]
    issued = call(
        "POST",
        f"/v1/admin/merchants/{merchant_id}/api-keys",
        {"environment": "TEST"},
        admin_key,
        {201},
    )

    if env.get("ITAU_SANDBOX_CLIENT_ID"):
        call(
            "PUT",
            f"/v1/admin/merchants/{merchant_id}/providers/ITAU/credentials",
            {
                "environment": "TEST",
                "payload": {
                    "client_id": env["ITAU_SANDBOX_CLIENT_ID"],
                    "client_secret": env["ITAU_SANDBOX_CLIENT_SECRET"],
                    "pix_key": env.get("ITAU_SANDBOX_PIX_KEY", "e2e@example.com"),
                    "beneficiary_id": env.get("ITAU_SANDBOX_BENEFICIARY_ID", "150000052061"),
                },
            },
            admin_key,
            {204},
        )
    if env.get("CIELO_SANDBOX_MERCHANT_ID"):
        call(
            "PUT",
            f"/v1/admin/merchants/{merchant_id}/providers/CIELO/credentials",
            {
                "environment": "TEST",
                "payload": {
                    "merchant_id": env["CIELO_SANDBOX_MERCHANT_ID"],
                    "merchant_key": env["CIELO_SANDBOX_MERCHANT_KEY"],
                },
            },
            admin_key,
            {204},
        )

    with open(ENV_PATH, "a", encoding="utf-8") as handle:
        handle.write(
            "\n# local dev merchant created by scripts/dev_merchant.py (the key is shown once by the API)\n"
            f"GATEWAY_DEV_MERCHANT_ID={merchant_id}\n"
            f"GATEWAY_DEV_API_KEY={issued['key']}\n"
        )
    print(f"merchant {merchant_id} created; TEST key saved to .env as GATEWAY_DEV_API_KEY")


if __name__ == "__main__":
    main()
