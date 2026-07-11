#!/usr/bin/env python3
"""Perform sanitized JSON-RPC reads against an isolated restored MCP server."""
from __future__ import annotations

import argparse
import json
import os
import re
import selectors
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


def send(process: subprocess.Popen[str], payload: dict[str, Any]) -> None:
    assert process.stdin is not None
    process.stdin.write(json.dumps(payload, separators=(",", ":")) + "\n")
    process.stdin.flush()


def response(process: subprocess.Popen[str], selector: selectors.BaseSelector, message_id: int, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("isolated MCP process exited early")
        if not selector.select(timeout=min(0.25, deadline - time.monotonic())):
            continue
        assert process.stdout is not None
        line = process.stdout.readline()
        try:
            decoded = json.loads(line)
        except json.JSONDecodeError as exc:
            raise RuntimeError("isolated MCP stdout was not JSON-RPC clean") from exc
        if decoded.get("id") == message_id:
            if "error" in decoded or decoded.get("result", {}).get("isError"):
                raise RuntimeError("isolated MCP request failed")
            return decoded
    raise TimeoutError("isolated MCP request timed out")


def call(process: subprocess.Popen[str], selector: selectors.BaseSelector, message_id: int, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    send(process, {"jsonrpc": "2.0", "id": message_id, "method": "tools/call", "params": {"name": name, "arguments": arguments}})
    return response(process, selector, message_id, 30)


def collection_size(result: dict[str, Any], key: str) -> int:
    structured = result.get("result", {}).get("structuredContent", {})
    values = structured.get(key, []) if isinstance(structured, dict) else []
    return len(values) if isinstance(values, list) else 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--image", required=True)
    parser.add_argument("--backup-sha256", required=True)
    parser.add_argument("--document-id")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("missing MCP command after --")
    registry_digest = re.fullmatch(r"[^\s@]+@sha256:[a-f0-9]{64}", args.image)
    test_image_id = os.environ.get("POSTGRES_OPS_TEST_MODE") == "1" and re.fullmatch(r"sha256:[a-f0-9]{64}", args.image)
    if not registry_digest and not test_image_id:
        parser.error("--image must be immutable")

    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
    selector = selectors.DefaultSelector()
    assert process.stdout is not None
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        send(process, {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "job-engine-backup-verify", "version": "1"}}})
        response(process, selector, 1, 60)
        send(process, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        health = call(process, selector, 2, "health", {})
        profiles = call(process, selector, 3, "list_profiles", {})
        jobs = call(process, selector, 4, "list_jobs", {})
        document_checked = False
        if args.document_id:
            call(process, selector, 5, "get_document_metadata", {"documentId": args.document_id})
            document_checked = True
        report = {"format": 1, "status": "verified", "image": args.image, "backupSha256": args.backup_sha256, "mcp": {"health": bool(health), "profilesReturned": collection_size(profiles, "profiles"), "jobsReturned": collection_size(jobs, "jobs"), "documentMetadataChecked": document_checked}}
        args.report.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with args.report.open("x", encoding="utf-8") as handle:
            json.dump(report, handle, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
        return 0
    finally:
        selector.close()
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        print("restored MCP verification failed", file=sys.stderr)
        raise SystemExit(1)
