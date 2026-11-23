#!/usr/bin/env python3
"""Run for one deployed bot with its production environment loaded."""
import argparse
import json
import os
import re
import sys
from hmac import compare_digest
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def origin(name):
    value = os.environ[name].rstrip("/")
    url = urlsplit(value)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.path or url.query or url.fragment:
        sys.exit(f"{name} must be an HTTPS origin")
    return value


def request(url, data=None, headers=None):
    body = None if data is None else json.dumps(data).encode()
    try:
        req = Request(url, data=body, headers={"Content-Type": "application/json", **(headers or {})})
        with build_opener(NoRedirect()).open(req, timeout=45) as response:
            return response.read()
    except HTTPError as error:
        sys.exit(f"Setup failed for {urlsplit(url).hostname}: HTTP {error.code}")
    except (URLError, TimeoutError):
        sys.exit(f"Setup failed: cannot reach {urlsplit(url).hostname}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=["auth", "training"])
    role = parser.parse_args().role
    bot = origin("BOT_PUBLIC_URL")
    platform = origin("PLATFORM_URL")
    token = os.environ["AUTH_BOT_TOKEN" if role == "auth" else "BOT_TOKEN"]
    key = os.environ["AUTH_SERVICE_KEY" if role == "auth" else "TRAINING_SERVICE_KEY"]
    secret = os.environ["TELEGRAM_WEBHOOK_SECRET"]
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", secret):
        sys.exit("Invalid TELEGRAM_WEBHOOK_SECRET")
    api = f"https://api.telegram.org/bot{token}/"
    username = json.loads(request(api + "getMe", {}))["result"]["username"]
    if not compare_digest(username.lower(), os.environ["BOT_USERNAME"].lstrip("@").lower()):
        sys.exit("BOT_USERNAME does not match the token")
    if request(bot + "/health") != b"OK":
        sys.exit("Unexpected bot health response")
    # A valid, intentionally ignored Telegram update verifies the production secret and route.
    request(bot + "/telegram", {"update_id": 0}, {"X-Telegram-Bot-Api-Secret-Token": secret})
    request(f"{platform}/internal/v1/{role}/identity", {"username": username}, {"Authorization": f"Bearer {key}"})
    url = bot + "/telegram"
    allowed = ["message", "callback_query"] if role == "training" else ["message"]
    result = json.loads(request(api + "setWebhook", {"url": url, "secret_token": secret,
                                                     "allowed_updates": allowed, "max_connections": 1}))
    info = json.loads(request(api + "getWebhookInfo", {}))["result"]
    if not result.get("ok") or info.get("url") != url or info.get("max_connections") != 1:
        sys.exit("Telegram did not confirm the expected webhook")
    print(f"@{username} → {url}")


if __name__ == "__main__":
    try:
        main()
    except KeyError as error:
        sys.exit(f"Missing required setting or response field: {error}")
