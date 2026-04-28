# Contributing to HiveMem

Thank you for your interest in contributing to HiveMem! This guide will help you get started with our development process.

## How to Contribute

### Reporting Bugs
- Use [GitHub Issues](https://github.com/ufelmann/HiveMem/issues) to report bugs.
- Include a clear description of the issue, steps to reproduce, and your environment (OS, Java version, Docker version).

### Suggesting Features
- Use [GitHub Discussions](https://github.com/ufelmann/HiveMem/discussions) to suggest and discuss new features.

### Pull Requests
1. Fork the repository.
2. Create a new branch for your feature or fix.
3. Write clean, documented code.
4. Add tests for any new functionality.
5. Ensure all tests pass (`cd java-server && mvn test`).
6. Update `documentation/` for any user-facing change (see checklist below).
7. Submit a Pull Request with a clear description of your changes.

**Documentation checklist** — tick what applies before submitting:

- [ ] New or removed MCP tool → updated `documentation/tools.md` and tool count badge in `README.md`
- [ ] New or changed config property → updated `documentation/hook/configuration.md` or `documentation/architecture.md`
- [ ] Hook pipeline behaviour changed → updated `documentation/hook/pipeline.md`
- [ ] Auth or role behaviour changed → updated `documentation/auth.md`
- [ ] Architecture or data model changed → updated `documentation/architecture.md`
- [ ] New deployment or ops step → updated `documentation/operations.md`
- [ ] No documentation impact (pure refactor / test / internal change)

## Development Setup

HiveMem requires Java 25, Maven, and Docker (for running tests with Testcontainers).

```bash
# Clone the repository
git clone https://github.com/ufelmann/HiveMem.git
cd HiveMem

# Run tests (Testcontainers starts a pgvector/pgvector:pg17 container automatically)
cd java-server
mvn test
```

No external database or embedding service is needed for testing. Tests use Testcontainers with an ephemeral PostgreSQL instance and a fixed embedding client stub.

## Code Style
- Follow standard Java conventions (Google Java Style as baseline).
- Use meaningful variable and method names.
- Write Javadoc for public APIs.
- Ensure test coverage for new logic.
- Use Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.

## Contributor License Agreement
By contributing to HiveMem, you agree that your contributions will be licensed under the terms of our [Contributor License Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md) and the project's [Sustainable Use License](LICENSE).
