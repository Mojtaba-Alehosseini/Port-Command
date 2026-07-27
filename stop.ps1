# Port Command Genova - stop every process the launcher may have left running (Windows).
#
# The normal path never needs this: start.ps1's finally block stops both services when the game
# exits, including on Ctrl-C. This is the escape hatch for when that window was killed hard -
# and the thing to run when ":5005 already in use" or the misleading null-container NPE from a
# JADE port-1099 clash shows up (docs/TROUBLESHOOTING.md).
#
# Two passes, in order:
#   1. the PID files start.ps1 wrote (precise: only this project's processes)
#   2. a command-line scan (the safety net for a run whose PID file was lost)
#
# Pass 2 matches on the FULL command line, never on the image name, so it cannot kill an
# unrelated python.exe or java.exe the user has open.
$ErrorActionPreference = 'Stop'

$Root   = $PSScriptRoot
$PidDir = Join-Path $Root 'port-command-genova\logs\pids'
$killed = 0

function Stop-Tree([int]$ProcessId, [string]$What) {
    if ($ProcessId -le 0) { return $false }
    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
        return $false   # already gone (usually killed as part of an earlier tree)
    }
    # `cmd /c` keeps taskkill's stderr inside cmd. Doing it in PowerShell (`2>&1`) wraps each
    # stderr line in a NativeCommandError and sets $? to $false even on a clean exit (PS 5.1).
    & cmd /c "taskkill /PID $ProcessId /T /F >nul 2>&1"
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        try { Stop-Process -Id $ProcessId -Force -ErrorAction Stop } catch { return $false }
    }
    if ($What) { Write-Host "[stop] killed $What (pid $ProcessId)" }
    return $true
}

# --- pass 1: recorded PIDs ---------------------------------------------------------------
# The PID file records "<pid> <imageName> <startTicks>". All three must match before anything is
# killed: Windows recycles pids, and after a reboot the recorded number almost certainly belongs to
# an unrelated process that this would otherwise taskkill /T /F.
Get-ChildItem -Path $PidDir -Filter '*.pid' -ErrorAction SilentlyContinue | ForEach-Object {
    $parts = ((Get-Content $_.FullName -ErrorAction SilentlyContinue | Select-Object -First 1) -split '\s+')
    if ($parts.Count -ge 3 -and $parts[0] -match '^\d+$') {
        $proc = Get-Process -Id ([int]$parts[0]) -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -eq $parts[1] -and "$($proc.StartTime.Ticks)" -eq $parts[2]) {
            if (Stop-Tree ([int]$parts[0]) $_.BaseName) { $script:killed++ }
        } elseif ($proc) {
            Write-Host "[stop] pid $($parts[0]) has been recycled by another process - not killing it"
        }
    }
    Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
}

# --- pass 2: command-line scan ------------------------------------------------------------
# Get-CimInstance Win32_Process is the PS 5.1 way to read another process's command line
# (Get-Process does not expose it).
$patterns = @(
    @{ Match = 'llm_sidecar'                      ; What = 'Flask LLM sidecar' },
    @{ Match = 'rasa'                             ; What = 'Rasa NLU server'   },
    @{ Match = 'it\.unige\.portcommand\.Main'     ; What = 'the game JVM'      }
)
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine } |
    ForEach-Object {
        $proc = $_
        foreach ($p in $patterns) {
            # Anchor Rasa on the venv path too: a bare "rasa" would also match this script's
            # own window title or an editor that happens to have the word on its command line.
            $needle = if ($p.Match -eq 'rasa') { '\.venv-rasa' } else { $p.Match }
            if ($proc.CommandLine -match $needle) {
                if (Stop-Tree ([int]$proc.ProcessId) $p.What) { $script:killed++ }
                break
            }
        }
    }

if ($killed -eq 0) {
    Write-Host '[stop] nothing was running.'
} else {
    Write-Host "[stop] stopped $killed process(es)."
}
