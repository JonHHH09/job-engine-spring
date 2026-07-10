#!/usr/bin/env python3
"""Regression tests for the MCP STDIO smoke harness."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parent.parent
SMOKE = ROOT / "scripts" / "smoke-mcp-stdio.py"

EXPECTED_TOOLS = [
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
]


class SmokeMcpStdioTests(unittest.TestCase):
    def test_large_stderr_does_not_deadlock_protocol(self) -> None:
        fake_server = textwrap.dedent(
            f"""
            import json
            import sys

            for _ in range(512):
                sys.stderr.write("diagnostic-" + "x" * 4096 + "\\n")
            sys.stderr.flush()

            for line in sys.stdin:
                request = json.loads(line)
                if request.get("method") == "initialize":
                    response = {{
                        "jsonrpc": "2.0",
                        "id": request["id"],
                        "result": {{"protocolVersion": "2024-11-05", "capabilities": {{}}, "serverInfo": {{"name": "fake", "version": "test"}}}},
                    }}
                    print(json.dumps(response), flush=True)
                elif request.get("method") == "tools/list":
                    tools = [{{"name": name}} for name in {json.dumps(EXPECTED_TOOLS)}]
                    print(json.dumps({{"jsonrpc": "2.0", "id": request["id"], "result": {{"tools": tools}}}}), flush=True)
            """
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            server_path = Path(temp_dir) / "fake_mcp_server.py"
            server_path.write_text(fake_server, encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SMOKE), "--timeout", "15", "--", sys.executable, str(server_path)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                timeout=30,
                check=False,
            )

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("MCP STDIO smoke passed with 25 tools.", completed.stdout)


if __name__ == "__main__":
    unittest.main()
