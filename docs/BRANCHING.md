# Branch and release workflow

This is the permanent workflow for HMXPDF. It exists so that every contributor,
maintainer, and automation session works on the same branch and never treats
`main` as a development branch.

## Branches

| Branch | Role | Writes |
|---|---|---|
| `release/hmxpdf-2.1.1-build` | **Primary development branch** and GitHub default branch | All normal work: features, fixes, documentation, website, CI, release preparation |
| `main` | **Backup snapshot** | Only an explicit, maintainer-requested fast-forward synchronization |
| `backup/*` | Frozen historical safety points | Never updated |

The repository default branch is `release/hmxpdf-2.1.1-build`, verified through
the GitHub API rather than assumed from a local clone.

## Before every task

Run these steps before changing files:

```bash
git rev-parse --show-toplevel          # confirm the repository root
git remote -v                          # confirm the GitHub remote
git fetch --prune origin
git switch release/hmxpdf-2.1.1-build  # never develop on main
git pull --ff-only
git status --short                     # preserve any existing local work
```

Also confirm the GitHub default branch:

```bash
gh repo view HMXPANEL/HMXPDF --json defaultBranchRef
```

or

```bash
curl -sS -H "Authorization: Bearer $TOKEN" \
  https://api.github.com/repos/HMXPANEL/HMXPDF \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['default_branch'])"
```

Expected output: `release/hmxpdf-2.1.1-build`.

## Normal development

1. Work on `release/hmxpdf-2.1.1-build`, directly or through a short-lived topic
   branch created from it.
2. Run the checks in [CONTRIBUTING.md](../CONTRIBUTING.md) that apply to the
   change.
3. Commit and push to `release/hmxpdf-2.1.1-build`:

   ```bash
   git push origin HEAD:release/hmxpdf-2.1.1-build
   ```

4. Confirm CI on the pushed commit and, for website changes, the Vercel
   production deployment.
5. Do **not** push the same commit to `main` as part of normal work.

## Backup synchronization (`main`)

`main` is updated only when the maintainer explicitly asks for a backup update.
The synchronization is a fast-forward of the verified primary branch:

1. Confirm the primary branch is the current default branch and is green in CI.
2. Confirm the working tree is clean and the local primary branch matches the
   remote.
3. Compare ancestry before writing anything:

   ```bash
   git fetch origin
   git merge-base --is-ancestor origin/main origin/release/hmxpdf-2.1.1-build \
     && echo "fast-forward safe" || echo "DIVERGED - stop and report"
   git rev-list --count origin/main..origin/release/hmxpdf-2.1.1-build
   git rev-list --count origin/release/hmxpdf-2.1.1-build..origin/main
   ```

4. If `main` has unique commits, **stop**. Do not force-push, reset, or discard
   them. Report the difference and agree on a merge strategy first.
5. If `main` is an ancestor and has no unique commits, fast-forward it:

   ```bash
   git push origin release/hmxpdf-2.1.1-build:main
   ```

6. Verify both heads and confirm the default branch is unchanged:

   ```bash
   git ls-remote origin refs/heads/release/hmxpdf-2.1.1-build refs/heads/main
   gh repo view HMXPANEL/HMXPDF --json defaultBranchRef
   ```

## Safety rules

- Never force-push (`--force`, `--force-with-lease`) to any shared branch.
- Never `git reset --hard`, delete branches, or discard uncommitted work.
- Never overwrite unique commits on `main` or on a backup branch.
- Never merge `main` into the primary branch as routine work.
- Never develop, commit, or push normal changes on `main`.
- Never change signing credentials, the package name, or the app version as part
  of a documentation or branding change.
- If a branch protection rule or permission blocks an operation, stop and report
  the exact limitation instead of disabling protection.

## CI and deployment

- `.github/workflows/android.yml` runs for pushes to
  `release/hmxpdf-2.1.1-build` and `main`, pull requests, version tags, and manual
  dispatch. The primary branch is the branch that must stay green.
- The release job builds signed artifacts from tag pushes, manual dispatch with
  `build_signed_release=true`, and pushes to the primary branch. It does not
  create GitHub Releases.
- Vercel project `hmxpdf-website` deploys `hmxwebsite/` from production branch
  `release/hmxpdf-2.1.1-build`. A push to the primary branch triggers the
  production deployment; pushing to `main` does not change the live site.
