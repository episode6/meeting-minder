# Enforce Docs & Changelog Updates Skill

This directory contains the skill definition for the docs/changelog update policy.

## Contents
- `skill.json`: Metadata about the skill.
- `SKILL.md`: The policy and how it's enforced.

## Usage
Any change that modifies production/source files must also update `CHANGELOG.md` and any relevant documentation. Run `scripts/verify-docs-updated.sh` before creating a PR; CI enforces the same check via `.github/workflows/verify-docs.yml`.
