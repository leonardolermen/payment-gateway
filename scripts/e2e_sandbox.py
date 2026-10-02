"""End-to-end happy path of every payment method against the real sandboxes, written as a report.

Runs against a gateway already up (`java -jar gateway-app/target/gateway-app-*.jar`) and the sandbox
credentials in `.env` (never printed: the report shows field names, never values). One merchant is
created per run, with a TEST key and both provider credentials; then one happy path per method:

  PIX       create -> get -> events -> cancel
  BOLECODE  create -> get -> events -> cancel
  CARD      authorize -> capture -> partial refund -> refunds -> events, plus a direct capture

The Itau sandbox is a static mock (docs/providers/itau/NOTES.md): it issues charges but nothing pays
them, so the Pix and Bolecode paths end at cancel. The Cielo sandbox simulates the whole card life.

    python scripts/e2e_sandbox.py                     # writes docs/e2e/<today>-sandbox-happy-paths.md
    GATEWAY=http://localhost:8080 ADMIN_KEY=dev-admin python scripts/e2e_sandbox.py
"""

import datetime
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = os.environ.get("GATEWAY", "http://localhost:8080")
ADMIN_KEY = os.environ.get("ADMIN_KEY", "dev-admin")
TODAY = datetime.date.today().isoformat()
REPORT = ROOT / "docs" / "e2e" / f"{TODAY}-sandbox-happy-paths.md"

# Values that must never reach the report, whatever field carries them.
HIDDEN_FIELDS = {"client_id", "client_secret", "merchant_id", "merchant_key", "pix_key", "key", "number", "cvv", "beneficiary_id"}
PAN_PATTERN = re.compile(r"\b\d{13,19}\b")


def load_env():
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


class Report:
    def __init__(self):
        self.lines = []
        self.failures = []

    def heading(self, level, text):
        self.lines += ["", "#" * level + " " + text, ""]

    def text(self, text):
        self.lines += [text, ""]

    def step(self, title, method, path, request, status, response, expect=None):
        ok = expect is None or status in expect
        mark = "OK" if ok else "UNEXPECTED"
        if not ok:
            self.failures.append(f"{title}: http {status}, expected {expect}")
        self.lines += [f"### {title} — `{method} {path}` → **{status} {mark}**", ""]
        if request is not None:
            self.lines += ["Request:", "", "```json", json.dumps(scrub(request), indent=2, ensure_ascii=False), "```", ""]
        self.lines += ["Response:", "", "```json", pretty(response), "```", ""]

    def write(self):
        REPORT.parent.mkdir(parents=True, exist_ok=True)
        REPORT.write_text("\n".join(self.lines).strip() + "\n", encoding="utf-8", newline="\n")


def scrub(value):
    if isinstance(value, dict):
        return {k: ("<hidden>" if k in HIDDEN_FIELDS else scrub(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v) for v in value]
    if isinstance(value, str):
        return PAN_PATTERN.sub("<digits>", value)
    return value


def pretty(response):
    try:
        text = json.dumps(scrub(json.loads(response)), indent=2, ensure_ascii=False)
    except (ValueError, TypeError):
        text = str(response)
    return text if len(text) <= 3000 else text[:3000] + "\n… (truncated)"


class Gateway:
    def __init__(self, report):
        self.report = report
        self.api_key = None

    def admin(self, title, method, path, body=None, expect=None):
        return self._call(title, method, path, body, {"X-Admin-Key": ADMIN_KEY}, expect, record_request=True)

    def merchant(self, title, method, path, body=None, expect=None, idempotency=None):
        headers = {"Authorization": "Bearer " + self.api_key}
        if idempotency:
            headers["Idempotency-Key"] = idempotency
        return self._call(title, method, path, body, headers, expect, record_request=True)

    def _call(self, title, method, path, body, headers, expect, record_request):
        data = json.dumps(body).encode() if body is not None else None
        headers = {**headers, "Content-Type": "application/json"}
        request = urllib.request.Request(GATEWAY + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                status, text = response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            status, text = error.code, error.read().decode()
        self.report.step(title, method, path, body if record_request else None, status, text, expect)
        try:
            return status, json.loads(text)
        except ValueError:
            return status, {}


def setup(gateway, env):
    gateway.report.heading(2, "Setup: one merchant, a TEST key, both sandbox credentials")
    _, merchant = gateway.admin("Create merchant", "POST", "/v1/admin/merchants", {"name": f"E2E {TODAY}"}, expect={201})
    merchant_id = merchant["id"]

    _, key = gateway.admin("Issue a TEST API key", "POST", f"/v1/admin/merchants/{merchant_id}/api-keys", {"environment": "TEST"}, expect={201})
    gateway.api_key = key["key"]

    itau = {
        "client_id": env["ITAU_SANDBOX_CLIENT_ID"],
        "client_secret": env["ITAU_SANDBOX_CLIENT_SECRET"],
        "pix_key": env.get("ITAU_SANDBOX_PIX_KEY", "e2e@example.com"),
        "beneficiary_id": env.get("ITAU_SANDBOX_BENEFICIARY_ID", "150000052061"),
    }
    gateway.admin("Register the Itaú TEST credential", "PUT", f"/v1/admin/merchants/{merchant_id}/providers/ITAU/credentials", {"environment": "TEST", "payload": itau}, expect={204})

    cielo = {"merchant_id": env["CIELO_SANDBOX_MERCHANT_ID"], "merchant_key": env["CIELO_SANDBOX_MERCHANT_KEY"]}
    gateway.admin("Register the Cielo TEST credential", "PUT", f"/v1/admin/merchants/{merchant_id}/providers/CIELO/credentials", {"environment": "TEST", "payload": cielo}, expect={204})
    return merchant_id


def pix(gateway):
    gateway.report.heading(2, "PIX (Itaú): create → get → events → cancel")
    body = {"method": "PIX", "amount": 1990, "currency": "BRL", "reference": "e2e-pix-1", "expires_in": 3600}
    _, payment = gateway.merchant("Create a Pix charge", "POST", "/v1/payments", body, expect={201}, idempotency="e2e-pix-1")
    payment_id = payment.get("id", "missing")

    gateway.merchant("Read it back", "GET", f"/v1/payments/{payment_id}", expect={200})
    gateway.merchant("Its events", "GET", f"/v1/payments/{payment_id}/events", expect={200})
    gateway.merchant("Cancel it (the bank removes the cob)", "POST", f"/v1/payments/{payment_id}/cancel", expect={200}, idempotency="e2e-pix-1-cancel")
    gateway.merchant("Read it back after the cancel", "GET", f"/v1/payments/{payment_id}", expect={200})


def bolecode(gateway):
    gateway.report.heading(2, "BOLECODE (Itaú): create → get → events → cancel")
    due = (datetime.date.today() + datetime.timedelta(days=5)).isoformat()
    body = {
        "method": "BOLECODE", "amount": 12990, "currency": "BRL", "reference": "e2e-bolecode-1",
        "due_date": due, "payment_limit_days": 30,
        "customer": {
            "name": "Ana Silva", "document": "529.982.247-25",
            "address": {"street": "Av. Paulista 1000", "district": "Bela Vista", "city": "São Paulo", "state": "SP", "zip": "01310-100"},
        },
    }
    status, payment = gateway.merchant("Issue a boleto with Pix", "POST", "/v1/payments", body, expect={201}, idempotency="e2e-bolecode-1")
    if status != 201:
        gateway.report.text(
            "The issue did not succeed, so the rest of this path was not run. As of 2026-09-30 the Itaú sandbox answers "
            "`500 Cenário de teste não mapeado` to every `POST /boletos-pix`, including the documentation's own example "
            "(docs/providers/itau/NOTES.md, \"Sandbox smoke\"); the gateway turns that into `422 PROVIDER_UNAVAILABLE`."
        )
        return
    payment_id = payment["id"]

    gateway.merchant("Read it back", "GET", f"/v1/payments/{payment_id}", expect={200})
    gateway.merchant("Its events", "GET", f"/v1/payments/{payment_id}/events", expect={200})
    gateway.merchant("Cancel it (baixa at the bank)", "POST", f"/v1/payments/{payment_id}/cancel", expect={200}, idempotency="e2e-bolecode-1-cancel")
    gateway.merchant("Read it back after the cancel", "GET", f"/v1/payments/{payment_id}", expect={200})


def card(gateway):
    gateway.report.heading(2, "CARD (Cielo): authorize → capture → partial refund → refunds → events")
    card_data = {"number": "4024007153763171", "holder": "JOAO DA SILVA", "expiry": "12/2030", "cvv": "123"}
    customer = {"name": "Joao da Silva", "document": "12345678901", "email": "joao@example.com"}

    body = {"method": "CARD", "amount": 10000, "currency": "BRL", "reference": "e2e-card-1", "soft_descriptor": "E2E",
            "installments": 1, "capture": False, "save_card": False, "card": card_data, "customer": customer}
    _, payment = gateway.merchant("Authorize without capturing", "POST", "/v1/payments", body, expect={201}, idempotency="e2e-card-1")
    payment_id = payment.get("id", "missing")

    gateway.merchant("Capture the full amount", "POST", f"/v1/payments/{payment_id}/capture", {"amount": 10000}, expect={200}, idempotency="e2e-card-1-capture")
    gateway.merchant("Refund part of it", "POST", f"/v1/payments/{payment_id}/refunds", {"amount": 2500}, expect={201}, idempotency="e2e-card-1-refund")
    gateway.merchant("List its refunds", "GET", f"/v1/payments/{payment_id}/refunds", expect={200})
    gateway.merchant("Read it back", "GET", f"/v1/payments/{payment_id}", expect={200})
    gateway.merchant("Its events", "GET", f"/v1/payments/{payment_id}/events", expect={200})

    gateway.report.heading(3, "And the one-call variant: authorize and capture together")
    body = {**body, "reference": "e2e-card-2", "capture": True, "installments": 3}
    gateway.merchant("Sale in 3 installments, captured at once", "POST", "/v1/payments", body, expect={201}, idempotency="e2e-card-2")


def main():
    env = load_env()
    report = Report()
    report.heading(1, f"End-to-end happy paths against the sandboxes — {TODAY}")
    report.text(
        "Generated by `scripts/e2e_sandbox.py` against a local gateway and the real Itaú and Cielo sandboxes. "
        "Credential values, API keys, card numbers and CVVs are replaced by `<hidden>`/`<digits>` before anything is written. "
        "The Itaú sandbox is a static mock: it issues Pix charges and boletos but nothing pays them, so those paths end at "
        "cancel; settlement, refund and reconciliation for Pix and Bolecode are covered by the WireMock integration tests. "
        "The Cielo sandbox simulates the whole card life, so the card path goes through capture and refund."
    )
    gateway = Gateway(report)
    try:
        setup(gateway, env)
        pix(gateway)
        bolecode(gateway)
        card(gateway)
    finally:
        report.heading(2, "Outcome")
        if report.failures:
            report.text("Steps that did not answer what the path expects:\n\n" + "\n".join(f"- {f}" for f in report.failures))
        else:
            report.text("Every step answered what the path expects.")
        report.write()
    print(f"report: {REPORT.relative_to(ROOT)}")
    for failure in report.failures:
        print("unexpected:", failure)
    sys.exit(1 if report.failures else 0)


if __name__ == "__main__":
    main()
