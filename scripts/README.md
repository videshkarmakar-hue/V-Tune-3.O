# V-Tune 3.O auto-push agent

A small local watcher that keeps this folder and the GitHub repository
[`videshkarmakar-hue/V-Tune-3.O`](https://github.com/videshkarmakar-hue/V-Tune-3.O) in sync.

It polls the working tree every 15 seconds. When edits have been quiet for
about two intervals, it:

1. `git add -A` (respects `.gitignore`)
2. commits with a timestamp, e.g. `auto: 2026-11-14 18:02:11`
3. rebases onto `origin/main` (retries up to 3 times)
4. pushes

## Start / stop

| Action | Command |
| --- | --- |
| Start (background) | `scripts\start-auto-push.cmd` |
| Stop | `scripts\stop-auto-push.cmd` |
| Run one sync now | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\auto-push.ps1 -Once` |
| Watch log | `Get-Content .auto-push\auto-push.log -Wait` |

## Start automatically at sign-in

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install-autostart.ps1
```

This creates a shortcut in your Windows Startup folder. Remove it with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\uninstall-autostart.ps1
```

## Notes

- Runtime state (`pid`, `lock`, `log`) lives in `.auto-push/`, which is git-ignored.
- Only one instance runs at a time (lock file + PID check).
- Authentication uses the Git Credential Manager entry for `https://github.com`.
  The repo is configured with the GitHub `noreply` author email because the
  account blocks pushes that expose a private email.
- Files matched by `.gitignore` (build output, APKs, keystores, `local.properties`)
  are never committed or pushed.
- If a rebase conflicts, the agent aborts the rebase and logs the error. Resolve
  the conflict manually, then the next change triggers a new sync.
- To pause the agent, run `scripts\stop-auto-push.cmd`. To disable autostart,
  run `scripts\uninstall-autostart.ps1`.
