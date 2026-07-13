## Summary

<!-- What changed, and why? Keep this focused. -->

## Tracker

<!-- Link the GitHub/Linear issue when one exists. -->

## Changes

-

## Verification

<!-- List exact commands and results. Do not claim checks that were not run. -->

- [ ] Focused tests:
- [ ] `./mvnw test`
- [ ] `./mvnw -Pintegration-tests verify` when persistence, Flyway, JDBC, startup security, coverage, or runtime behavior changed
- [ ] `git diff --check`
- [ ] Relevant HTTP/STDIO/container/operational checks when applicable

## Impact checklist

- [ ] I used only synthetic test/report data and included no credentials, private career data, backups, keys, tokens, or production logs.
- [ ] I considered untrusted inputs, error/log leakage, local file access, network binding, SSRF, and data retention where relevant.
- [ ] I preserved loopback-only Streamable HTTP and private PostgreSQL, or documented the approved architecture change.
- [ ] I added a new Flyway migration instead of editing an applied migration, or no schema change was required.
- [ ] I documented MCP tool/schema, configuration, migration, compatibility, and release impact, or none applies.
- [ ] I updated user/contributor documentation where behavior changed.

## Database or configuration changes

<!-- Describe migrations, new/changed environment variables, defaults, and rollback implications. Write "None" when not applicable. -->

## MCP compatibility and release impact

<!-- Describe tool names/arguments/responses, client reload requirements, deployment changes, and release implications. Write "None" when not applicable. -->

## Limitations

<!-- State checks not run, unsupported environments, or remaining risks. -->
