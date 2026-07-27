# PR with Codex Review

Create a PR, trigger a Codex review, and iterate until the reviewer signs off.

## When to use

After committing a change set that's ready to merge. Use any time you're creating a PR for this project.

## Steps

### 1. Commit and push to a feature branch

```bash
# Ensure you're on a named branch (not main)
git checkout -b <branch-name>
git push -u origin <branch-name>
```

### 2. Create the PR

```bash
gh pr create --title "<title>" --body "$(cat <<'EOF'
## Summary
<bullet points>

## Test plan
- [ ] item

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

### 3. Post the review request comment

```bash
gh pr comment <PR#> --body "@codex review"
```

### 4. Poll for review feedback

Check PR comments roughly every 60 seconds until Codex responds:

```bash
gh pr view <PR#> --comments
```

Keep polling until you see a comment from the reviewer containing a thumbs-up (👍), "LGTM", "all good", or an approval. If Codex posts issues instead, proceed to step 5.

### 5. Fix legitimate issues and re-request review

For each real issue Codex raises:
- Decide if it's legitimate (not a false positive or a matter of style preference)
- If legitimate: fix it, commit on the same branch, push
- After all fixes are committed, post `@codex review` again as a new comment

Repeat steps 4–5 until the review passes.

### 6. Merge

Once approved, merge via:

```bash
gh pr merge <PR#> --squash --delete-branch
```

Or let the human do it if they prefer to review themselves first.

## Notes

- Don't fix Codex comments that are matters of opinion or contradict project conventions (check CLAUDE.md)
- When polling, `gh pr view <PR#> --comments` shows all comments; look for the newest one
- If Codex never responds after several minutes, ping the user — the bot may need manual triggering
