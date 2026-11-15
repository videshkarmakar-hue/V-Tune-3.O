#!/usr/bin/env python3
"""
Release notification agent (GitHub-side).

Triggered from CI (e.g. on release published) to notify external systems and, if
configured, send an FCM push so app installs can be alerted promptly even when
the app is closed.

Configuration via environment variables:
- RELEASE_TAG / RELEASE_NAME / RELEASE_URL / RELEASE_BODY (typically from GitHub event)
- FCM_SERVER_KEY: Firebase Cloud Messaging legacy server key (optional)
- FCM_TOPIC: FCM topic to notify (default: app-updates)
- RELEASE_NOTIFY_WEBHOOK_URL: generic webhook to POST JSON payload to (optional)

This script is best-effort: missing optional configuration is reported but does
not fail the run unless FCM/webhook is configured and the send itself fails.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple


def log(level: str, msg: str) -> None:
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"[{ts}] [{level}] {msg}", flush=True)


def env(name: str, default: str = "") -> str:
    return (os.getenv(name) or default).strip()


def build_payload() -> Dict[str, Any]:
    tag = env("RELEASE_TAG") or env("GITHUB_REF_NAME")
    name = env("RELEASE_NAME") or tag or "New release"
    url = env("RELEASE_URL")
    body = env("RELEASE_BODY")
    version = tag.lstrip("v") if tag.startswith("v") else tag
    return {
        "event": "release_published",
        "app": "V-Tune 3.O",
        "tag": tag,
        "version": version,
        "name": name,
        "url": url,
        "notes": body,
    }


def http_post_json(url: str, payload: Dict[str, Any], headers: Optional[Dict[str, str]] = None, timeout: int = 20) -> Tuple[int, str]:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            return resp.getcode(), body
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace") if hasattr(e, "read") else str(e)
        return e.code, body


def notify_fcm(payload: Dict[str, Any]) -> bool:
    server_key = env("FCM_SERVER_KEY")
    topic = env("FCM_TOPIC", "app-updates")
    if not server_key:
        log("INFO", "FCM_SERVER_KEY not configured; skipping FCM push (app periodic checks will still detect releases).")
        return False

    version = payload.get("version") or payload.get("tag") or ""
    title = "New update available"
    body = f"V-Tune 3.O {version} is available.".strip()
    fcm_payload = {
        "to": f"/topics/{topic}",
        "priority": "high",
        "notification": {
            "title": title,
            "body": body,
        },
        "data": {
            "type": "app_release_update",
            "tag": str(payload.get("tag") or ""),
            "version": str(version),
            "name": str(payload.get("name") or ""),
            "url": str(payload.get("url") or ""),
        },
    }
    code, resp = http_post_json(
        "https://fcm.googleapis.com/fcm/send",
        fcm_payload,
        headers={"Authorization": f"key={server_key}"},
    )
    if 200 <= code < 300:
        log("INFO", f"FCM notification sent to topic '{topic}' (HTTP {code}).")
        return True
    log("ERROR", f"FCM send failed (HTTP {code}): {resp}")
    raise RuntimeError(f"FCM send failed with HTTP {code}")


def notify_webhook(payload: Dict[str, Any]) -> bool:
    url = env("RELEASE_NOTIFY_WEBHOOK_URL")
    if not url:
        log("INFO", "RELEASE_NOTIFY_WEBHOOK_URL not configured; skipping webhook notification.")
        return False
    code, resp = http_post_json(url, payload)
    if 200 <= code < 300:
        log("INFO", f"Webhook notified (HTTP {code}).")
        return True
    log("ERROR", f"Webhook notification failed (HTTP {code}): {resp}")
    raise RuntimeError(f"Webhook notification failed with HTTP {code}")


def main() -> int:
    payload = build_payload()
    log("INFO", f"Release notify payload: tag={payload.get('tag')} version={payload.get('version')} url={payload.get('url')}")

    did_any = False
    errors = []
    try:
        did_any = notify_fcm(payload) or did_any
    except Exception as e:
        errors.append(str(e))
    try:
        did_any = notify_webhook(payload) or did_any
    except Exception as e:
        errors.append(str(e))

    if errors:
        for e in errors:
            log("ERROR", e)
        return 1

    if not did_any:
        log("INFO", "No external push/webhook configured. In-app periodic/background release checks will still notify users when they run.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
