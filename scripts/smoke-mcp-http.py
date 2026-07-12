#!/usr/bin/env python3
"""Smoke-test an MCP Streamable HTTP server with initialize, tools/list, and health."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from typing import Any

PROTOCOL_VERSION = "2025-03-26"


def decode_response(body: bytes, content_type: str) -> dict[str, Any]:
    if not body:
        return {}
    text = body.decode("utf-8")
    if "text/event-stream" in content_type:
        data_lines = [line[5:].strip() for line in text.splitlines() if line.startswith("data:")]
        if not data_lines:
            raise ValueError("SSE response did not contain a data event")
        text = data_lines[-1]
    payload = json.loads(text)
    if not isinstance(payload, dict):
        raise ValueError("MCP response must be a JSON object")
    return payload


def request(url: str, payload: dict[str, Any], session_id: str | None, timeout: float) -> tuple[dict[str, Any], str | None]:
    headers = {
        "Accept": "application/json, text/event-stream",
        "Content-Type": "application/json",
    }
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        result = decode_response(response.read(), response.headers.get("Content-Type", ""))
        return result, response.headers.get("Mcp-Session-Id", session_id)


def assert_result(response: dict[str, Any], request_id: int) -> dict[str, Any]:
    if response.get("id") != request_id:
        raise ValueError(f"MCP response id mismatch: expected {request_id}, got {response.get('id')}")
    if "error" in response:
        raise ValueError(f"MCP request failed: {response['error']}")
    result = response.get("result")
    if not isinstance(result, dict):
        raise ValueError("MCP response result must be an object")
    return result


def smoke(url: str, timeout: float) -> int:
    initialize, session_id = request(
        url,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "job-engine-http-smoke", "version": "1.0"},
            },
        },
        None,
        timeout,
    )
    initialize_result = assert_result(initialize, 1)
    if not initialize_result.get("serverInfo"):
        raise ValueError("initialize response did not include serverInfo")

    request(
        url,
        {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        session_id,
        timeout,
    )
    tools_response, session_id = request(
        url,
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
        session_id,
        timeout,
    )
    tools = assert_result(tools_response, 2).get("tools")
    if not isinstance(tools, list) or not tools:
        raise ValueError("tools/list returned no tools")
    names = {tool.get("name") for tool in tools if isinstance(tool, dict)}
    if "health" not in names:
        raise ValueError("tools/list did not expose health")

    health_response, _ = request(
        url,
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "health", "arguments": {}}},
        session_id,
        timeout,
    )
    health = assert_result(health_response, 3)
    if health.get("isError") is True:
        raise ValueError("health tool returned an MCP error")

    print(f"MCP Streamable HTTP smoke passed with {len(names)} tools.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8080/mcp")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()
    try:
        return smoke(args.url, args.timeout)
    except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError) as exc:
        print(f"MCP Streamable HTTP smoke failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
