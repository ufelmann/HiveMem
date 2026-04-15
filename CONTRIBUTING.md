# Contributing to HiveMem

Thank you for your interest in contributing to HiveMem! This guide will help you get started with our development process.

## How to Contribute

### Reporting Bugs
- Use [GitHub Issues](https://github.com/ufelmann/HiveMem/issues) to report bugs.
- Include a clear description of the issue, steps to reproduce, and your environment (OS, Python version).

### Suggesting Features
- Use [GitHub Discussions](https://github.com/ufelmann/HiveMem/discussions) to suggest and discuss new features.

### Pull Requests
1. Fork the repository.
2. Create a new branch for your feature or fix.
3. Write clean, documented code with type hints.
4. Add tests for any new functionality.
5. Ensure all tests pass (`pytest tests/ -v`).
6. Submit a Pull Request with a clear description of your changes.

## Development Setup

HiveMem requires Python 3.11+ and Docker for running tests with PostgreSQL.

```bash
# Clone the repository
git clone https://github.com/ufelmann/HiveMem.git
cd HiveMem

# Install development dependencies
pip install -e ".[dev]"

# Run tests
pytest tests/ -v
```

## Code Style
- Follow PEP 8 guidelines.
- Use Python type hints for all function signatures.
- Write descriptive docstrings.
- Ensure 100% test coverage for new logic.

## Contributor License Agreement
By contributing to HiveMem, you agree that your contributions will be licensed under the terms of our [Contributor License Agreement](CONTRIBUTOR_LICENSE_AGREEMENT.md) and the project's [Sustainable Use License](LICENSE).
