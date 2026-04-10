# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public issue
2. Email: ufelmann@gmail.com
3. Include steps to reproduce and potential impact

You can expect an initial response within 72 hours.

## Security Architecture

HiveMem uses:
- DB-backed API tokens with SHA-256 hashing
- 4-role permission system (admin, writer, reader, agent)
- Parameterized SQL queries (no string interpolation)
- Path traversal protection on file imports
- Rate limiting on failed auth attempts
