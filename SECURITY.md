# Security Policy

## Supported versions

Security fixes are provided for the latest published GitHub Release. Older releases, source snapshots, feature branches, and development builds are not supported. Upgrade to the latest release before reporting a problem that may already be fixed.

## Reporting a vulnerability

Do not open a public issue, discussion, pull request, or review comment for a suspected vulnerability.

Use GitHub's private vulnerability reporting workflow:

[Report a vulnerability privately](https://github.com/JonHHH09/job-engine-spring/security/advisories/new)

If that workflow is unavailable, contact the maintainers through the repository owner's GitHub profile and request a private reporting channel. Do not include vulnerability details, credentials, private career data, or exploit material in that initial message.

Include only the information needed to reproduce and assess the issue:

- affected release version or immutable container digest;
- operating system, CPU architecture, Java version, and deployment mode;
- whether the issue affects Streamable HTTP, isolated STDIO, or both;
- minimal reproduction steps using synthetic data;
- expected and observed security boundaries;
- impact assessment and any known mitigations; and
- whether the vulnerability is already public or actively exploited.

Never attach real resumes, profile records, job applications, database backups, access tokens, credentials, private keys, or production logs. Replace private values with clearly synthetic placeholders.

The maintainers aim to acknowledge a complete report within five business days and provide an initial assessment or request for additional information within ten business days. Resolution timing depends on severity, reproducibility, and release complexity. Please allow coordinated remediation and release publication before public disclosure.

## Security model

`job-engine-spring` is a local-only MCP backend, not a public web service or REST API. Its supported network deployment publishes the Streamable HTTP MCP endpoint on host loopback only and does not publish PostgreSQL. STDIO is reserved for isolated diagnostics and package verification.

The application treats PDFs, resumes, job pages, provider output, and MCP arguments as untrusted data. Reports involving these inputs must use synthetic fixtures. A configuration that publishes MCP or PostgreSQL to an untrusted network is outside the supported threat model.

Security-sensitive areas include:

- MCP transport binding and startup validation;
- SSRF and redirect handling in job-link ingestion;
- local file import boundaries and path validation;
- document, profile, job, match, and resume data isolation;
- PostgreSQL backup, verification, and restore safeguards;
- container images, release artifacts, signatures, attestations, and SBOMs; and
- accidental disclosure through logs, errors, generated files, or MCP responses.

## Good-faith research

Limit testing to systems and data you own or are explicitly authorized to test. Avoid privacy violations, data destruction, persistence, denial of service, supply-chain publication, and access to other users' information. Stop testing and report privately if you encounter private data or credentials.

Under this policy, good-faith testing is limited to copies of `job-engine-spring` and infrastructure that you own or have explicit permission to test. It does not give permission to test GitHub, package registries, job boards, or any other third-party service.
