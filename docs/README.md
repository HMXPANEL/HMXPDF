# Documentation index

HMXPDF keeps its user-facing policies at the repository root and its engineering
evidence in this folder.

## Current reference

| Document | Purpose |
|---|---|
| [README](../README.md) | Project overview, features, formats, build and test instructions |
| [CONTRIBUTING](../CONTRIBUTING.md) | Branching, setup, conventions, testing, review |
| [SECURITY](../SECURITY.md) | Private vulnerability reporting and scope |
| [CODE_OF_CONDUCT](../CODE_OF_CONDUCT.md) | Contributor Covenant 2.1 |
| [PRIVACY](../PRIVACY.md) | Data flows, network use, permissions |
| [CHANGELOG](../CHANGELOG.md) | Development history derived from Git |
| [BRANCHING](BRANCHING.md) | Primary/backup branch policy and backup synchronization procedure |
| [BRANDING_AUDIT](BRANDING_AUDIT.md) | Remaining old-branding audit and asset replacements |
| [DOCUMENTATION_AUDIT](DOCUMENTATION_AUDIT.md) | What was wrong in the docs and what changed |
| [LICENSING_AUDIT](LICENSING_AUDIT.md) | Licensing evidence and decision |
| [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md) | Component licenses and attribution |

## Engineering reports (dated snapshots)

These are historical records of audits and phases. They describe the repository
at the commit named in each report and are **not** rewritten when later work
changes the answer.

| Folder | Contents |
|---|---|
| [`fresh-audit/`](fresh-audit/) | Source audit: architecture, features, defects, test coverage, security and privacy |
| [`phase1/`](phase1/), [`phase1.1/`](phase1.1/), [`phase1-completion/`](phase1-completion/) | DOCX find/replace, comments, edit-mode gating |
| [`phase2/`](phase2/) | Track changes and revisions |
| [`phase3/`](phase3/), [`phase3-button-audit/`](phase3-button-audit/) | Page setup, headers and footers |
| [`phase-completion/`](phase-completion/) | Phase 4.0–4.6 completion reports: save foundation, autosave and recovery, tables, notes, spell check, read aloud |
| [`releases/`](releases/) | Main-branch publication report |
| `AUTONOMOUS_DEVELOPMENT_MASTER_LOG.md` | Running log of autonomous development sessions |

## Known stale content

- `fresh-audit/HMXPDF_COMPLETE_FEATURE_INVENTORY.md` marks autosave, advanced
  tables, spell check, footnotes, and read aloud (F55–F59) as not implemented. That
  was correct when written; all five were implemented in Phase 4. Read the
  `phase-completion/` reports for their current status.
- Several phase reports exist in two folders with the same file name. They are
  kept because each records a different CI run.
