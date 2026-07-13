# Support

## Start with the documentation

For setup and normal operation, use the [README](README.md). It covers the supported loopback-only MCP deployment, MCP client connection, health verification, persistence, backup, upgrade, shutdown, and uninstall paths.

This project is a local MCP backend. It does not provide a hosted service, public REST API, web UI, or access to third-party job boards.

## Bugs

Use the GitHub bug-report form when the documented path behaves incorrectly. Before filing:

- reproduce the problem on the latest published release or current `development` branch;
- search existing issues;
- reduce the report to synthetic data; and
- include exact commands, expected behavior, observed behavior, deployment mode, operating system, architecture, Java version, and relevant sanitized logs.

Never attach real resumes, profiles, job applications, database backups, credentials, private keys, access tokens, or production logs.

## Feature requests

Use the GitHub feature-request form. Explain the user problem, proposed behavior, alternatives, MCP compatibility impact, privacy/security implications, and how the result can be verified. A proposal does not imply acceptance or a delivery commitment.

## Usage questions

GitHub Discussions are not currently enabled. If the README is unclear, first search existing issues. Open a bug report only when a documented supported workflow fails reproducibly; otherwise propose a focused documentation improvement through the feature-request form.

## Security vulnerabilities

Do not report suspected vulnerabilities in public issues, pull requests, comments, or discussions. Follow [SECURITY.md](SECURITY.md) and use GitHub private vulnerability reporting.

## Supported versions and response expectations

Security fixes target the latest published GitHub Release as described in [SECURITY.md](SECURITY.md). General support is best-effort. Maintainers may close reports that cannot be reproduced, are outside the supported local-only threat model, concern unsupported releases, or lack enough sanitized evidence to investigate.
