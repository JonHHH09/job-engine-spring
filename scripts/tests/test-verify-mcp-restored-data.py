#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
VERIFY = ROOT / "scripts" / "verify-mcp-restored-data.py"
DIGEST = "registry.example/job-engine@sha256:" + "a" * 64


class VerifyMcpRestoredDataTests(unittest.TestCase):
    def test_writes_sanitized_report_after_required_reads(self) -> None:
        server = textwrap.dedent(
            """
            import json, sys
            for line in sys.stdin:
                request = json.loads(line)
                if request.get('method') == 'initialize':
                    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':{}}), flush=True)
                elif request.get('method') == 'tools/call':
                    name = request['params']['name']
                    structured = {'profiles': []} if name == 'list_profiles' else {'jobs': []} if name == 'list_jobs' else {}
                    print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':{'structuredContent': structured}}), flush=True)
            """
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "fake.py"
            report = root / "report.json"
            fake.write_text(server, encoding="utf-8")
            completed = subprocess.run([sys.executable, str(VERIFY), "--report", str(report), "--image", DIGEST, "--backup-sha256", "b" * 64, "--document-id", "00000000-0000-0000-0000-000000000000", "--", sys.executable, str(fake)], capture_output=True, text=True, timeout=30, check=False)
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual("mcp-verified", result["status"])
        self.assertTrue(result["mcp"]["documentMetadataChecked"])
        self.assertNotIn(str(root), json.dumps(result))

    def test_rejects_mutable_image(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            completed = subprocess.run([sys.executable, str(VERIFY), "--report", str(Path(directory) / "report.json"), "--image", "job-engine:latest", "--backup-sha256", "a" * 64], capture_output=True, text=True, check=False)
        self.assertNotEqual(0, completed.returncode)


if __name__ == "__main__":
    unittest.main()
