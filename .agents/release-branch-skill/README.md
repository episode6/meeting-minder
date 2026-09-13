# Cut Release Branch Skill

This directory contains the skill definition for cutting a new release branch and preparing the version-bump PRs.

## Contents
- `skill.json`: Metadata about the skill.
- `SKILL.md`: Detailed instructions and steps for the agent to follow.

## Usage
When tasked with cutting a release branch, the agent should follow the steps outlined in `SKILL.md`: verify main is green, create `release/v<VERSION>`, and open the snapshot-on-main and release-on-branch version-bump PRs that update `self.versions.toml` and `CHANGELOG.md`.
