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
    "acknowledge_match_disagreement",
    "add_job_from_analysis",
    "add_job_from_link",
    "add_job_from_text",
    "analyze_all_job_matches",
    "analyze_job_link",
    "analyze_job_match",
    "create_profile",
    "delete_job",
    "delete_profile",
    "extract_pdf_text",
    "extract_stored_pdf_text",
    "generate_canadian_french_pdf_resume",
    "generate_canadian_pdf_resume",
    "generate_german_cover_letter",
    "generate_german_tailored_resume",
    "generate_pdf_file",
    "generate_pdf_resume",
    "get_document_metadata",
    "get_job",
    "get_match_report",
    "get_match_review",
    "get_profile",
    "get_profile_pdf_source",
    "health",
    "import_arbeitnow_job",
    "ingest_profile_from_stored_pdf",
    "link_match_disagreement",
    "list_jobs",
    "list_match_disagreements",
    "list_match_reports",
    "list_match_reviews",
    "list_profiles",
    "scan_arbeitnow_jobs",
    "search_jobs",
    "search_profiles",
    "store_document_file",
    "submit_match_review",
    "update_job",
    "update_profile",
    "update_profile_project",
]


class SmokeMcpStdioTests(unittest.TestCase):
    def run_fake_server(self, tool_names: list[str], *, large_stderr: bool = False) -> subprocess.CompletedProcess[str]:
        stderr_setup = textwrap.indent('''
for _ in range(512):
    sys.stderr.write("diagnostic-" + "x" * 4096 + "\\n")
sys.stderr.flush()
''', "            ") if large_stderr else ""
        fake_server = textwrap.dedent(
            f"""
            import json
            import sys

            {stderr_setup}

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
                    tools = [{{"name": name}} for name in {json.dumps(tool_names)}]
                    print(json.dumps({{"jsonrpc": "2.0", "id": request["id"], "result": {{"tools": tools}}}}), flush=True)
            """
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            server_path = Path(temp_dir) / "fake_mcp_server.py"
            server_path.write_text(fake_server, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(SMOKE), "--timeout", "15", "--", sys.executable, str(server_path)],
                cwd=ROOT,
                text=True,
                capture_output=True,
                timeout=30,
                check=False,
            )

    def test_exact_source_grounded_tools_pass_and_report_count(self) -> None:
        completed = self.run_fake_server(EXPECTED_TOOLS, large_stderr=True)

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertIn("MCP STDIO smoke passed with 41 tools.", completed.stdout)

    def test_missing_expected_tool_fails_with_bounded_missing_message(self) -> None:
        completed = self.run_fake_server([name for name in EXPECTED_TOOLS if name != "health"])

        self.assertEqual(1, completed.returncode)
        self.assertIn("missing expected tools: health; unexpected tools: none", completed.stderr)
        self.assertLess(len(completed.stderr), 500)

    def test_unexpected_tool_fails_with_bounded_unexpected_message(self) -> None:
        completed = self.run_fake_server([*EXPECTED_TOOLS, "unexpected_tool"])

        self.assertEqual(1, completed.returncode)
        self.assertIn("missing expected tools: none; unexpected tools: unexpected_tool", completed.stderr)
        self.assertLess(len(completed.stderr), 500)


if __name__ == "__main__":
    unittest.main()
