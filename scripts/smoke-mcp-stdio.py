#!/usr/bin/env python3
"""Smoke-test an MCP STDIO server with initialize + tools/list.

Usage:
  scripts/smoke-mcp-stdio.py -- java -jar target/job-engine-spring-0.0.1-SNAPSHOT.jar
  scripts/smoke-mcp-stdio.py -- ./scripts/run-local-mcp-container.sh
"""

from __future__ import annotations

import argparse
import json
import selectors
import subprocess
import sys
import time
from typing import Any

EXPECTED_TOOLS = {
    "health",
    "list_profiles",
    "search_profiles",
    "get_profile",
    "create_profile",
    "update_profile",
    "delete_profile",
    "extract_pdf_text",
    "store_document_file",
    "get_document_metadata",
    "extract_stored_pdf_text",
    "generate_pdf_file",
    "generate_pdf_resume",
    "generate_canadian_pdf_resume",
    "ingest_profile_from_stored_pdf",
    "get_profile_pdf_source",
    "list_jobs",
    "search_jobs",
    "get_job",
    "update_job",
    "delete_job",
    "add_job_from_text",
    "add_job_from_link",
    "analyze_job_link",
    "add_job_from_analysis",
}


def send(proc: subprocess.Popen[str], payload: dict[str, Any]) -> None:
    assert proc.stdin is not None
    proc.stdin.write(json.dumps(payload, separators=(",", ":")) + "\n")
    proc.stdin.flush()


def read_json_line(proc: subprocess.Popen[str], selector: selectors.BaseSelector, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"MCP server exited early with code {proc.returncode}")
        events = selector.select(timeout=0.25)
        if not events:
            continue
        assert proc.stdout is not None
        line = proc.stdout.readline()
        if not line:
            continue
        try:
            return json.loads(line)
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"MCP stdout was not JSON-RPC clean: {line[:200]!r}") from exc
    raise TimeoutError("Timed out waiting for MCP JSON-RPC response")


def read_until_id(proc: subprocess.Popen[str], selector: selectors.BaseSelector, message_id: int, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        response = read_json_line(proc, selector, max(0.1, deadline - time.monotonic()))
        if response.get("id") == message_id:
            return response
    raise TimeoutError(f"Timed out waiting for MCP response id={message_id}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", nargs=argparse.REMAINDER, help="Command after -- that starts the MCP STDIO server")
    parser.add_argument("--timeout", type=float, default=90.0)
    args = parser.parse_args()

    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("missing MCP server command after --")

    proc = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    selector = selectors.DefaultSelector()
    assert proc.stdout is not None
    selector.register(proc.stdout, selectors.EVENT_READ)

    try:
        send(
            proc,
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "job-engine-spring-ci", "version": "0.1.0"},
                },
            },
        )
        init_response = read_until_id(proc, selector, 1, args.timeout)
        if "error" in init_response:
            raise RuntimeError(f"initialize failed: {init_response['error']}")

        send(proc, {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
        send(proc, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        tools_response = read_until_id(proc, selector, 2, args.timeout)
        if "error" in tools_response:
            raise RuntimeError(f"tools/list failed: {tools_response['error']}")

        tools = tools_response.get("result", {}).get("tools", [])
        names = {tool.get("name") for tool in tools if isinstance(tool, dict)}
        missing = sorted(EXPECTED_TOOLS - names)
        if missing:
            raise RuntimeError(f"tools/list missing expected tools: {', '.join(missing)}")

        print(f"MCP STDIO smoke passed with {len(names)} tools.")
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        stderr = proc.stderr.read() if proc.stderr is not None else ""
        if proc.returncode not in (0, -15, 143) and stderr:
            sys.stderr.write(stderr[-4000:])


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"MCP STDIO smoke failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
