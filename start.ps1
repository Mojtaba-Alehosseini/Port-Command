# Port Command Genova - the demo launcher (Windows).
#
# Boots the three processes in the order the game needs them, health-checking between each:
#
#   1. Rasa NLU server      :5005   from nlp-python/.venv-rasa   (Python 3.10 - rasa 3.6.21
#                                    refuses 3.11, see BUILD_LOG task 12)
#   2. Flask LLM sidecar    :5006   from nlp-python/.venv        (Python 3.11, LLM_QUANT=onnx)
#   3. The Java game        :1099   ./gradlew run --args="--gui"
#
# TWO VENVS, ONE PER SERVICE. planning/26's sketch activated a single venv for both; that
# predates the two-venv split and cannot work - rasa 3.6.21 will not install on 3.11 and the
# ONNX Runtime GenAI wheel the sidecar needs is installed only in .venv. Each service is
# launched from its OWN interpreter here, so nothing is "activated" at all.
#
# Teardown: the game runs in the FOREGROUND, and the finally block below stops both services
# whether it exits normally, throws, or is interrupted with Ctrl-C. If teardown is ever missed
# (a hard kill of this window, a bluescreen), the next start reaps the stale PIDs before it
# begins, and stop.bat is always available as the manual escape hatch.
#
# Written in PowerShell rather than pure cmd (dated deviation 2026-07-27 from planning/26's
# .bat sketch): cmd.exe answers Ctrl-C with "Terminate batch job (Y/N)?" and, on Y, skips the
# rest of the script - the sketch's teardown lines would silently never run and leak two
# servers holding :5005 and :5006. start.bat is a thin wrapper over this file.

[CmdletBinding()]
param(
    # Record the run to logs/profile.jfr (the 15-minute crash-free profiling criterion).
    [switch]$Profile,
    # Seconds of JFR recording; ignored unless -Profile.
    [int]$ProfileSeconds = 900,
    # tutorial | busy_day | storm. Omitted = the in-game New Game dialog.
    [string]$Scenario = '',
    # JADE Main Container port. 1099 is also the default Windows RMI registry port; if something
    # else holds it, pass e.g. -JadePort 11099 (see docs/TROUBLESHOOTING.md).
    [int]$JadePort = 1099,
    # Launch the game only - assume Rasa and the sidecar are already running.
    [switch]$NoServices,
    # Skip the LLM sidecar. Explanations fall back to the plain template; nothing else changes.
    [switch]$NoLlm
)

$ErrorActionPreference = 'Stop'

$Root    = $PSScriptRoot
$Nlp     = Join-Path $Root 'nlp-python'
$Game    = Join-Path $Root 'port-command-genova'
$LogDir  = Join-Path $Game 'logs'
$PidDir  = Join-Path $LogDir 'pids'
New-Item -ItemType Directory -Force -Path $PidDir | Out-Null

$RasaPy   = Join-Path $Nlp '.venv-rasa\Scripts\rasa.exe'
$SidecarPy = Join-Path $Nlp '.venv\Scripts\python.exe'
$Model    = Join-Path $Nlp 'rasa\models\portcmd_nlu.tar.gz'

$script:Started = @()   # [pscustomobject]@{ Name; Process; PidFile }

function Write-Step($msg) { Write-Host "[start] $msg" -ForegroundColor Cyan }
function Write-Warn($msg) { Write-Host "[start] $msg" -ForegroundColor Yellow }

# --- stale-process reaping ---------------------------------------------------------------
# A previous run that was killed hard leaves its PID files behind. Reap them BEFORE binding
# anything, or the health checks below would happily pass against the OLD servers (a stale
# Rasa still answers /status, with the previous model loaded - the exact silent-wrong-result
# failure this whole script exists to avoid).
function Stop-StaleProcesses {
    Get-ChildItem -Path $PidDir -Filter '*.pid' -ErrorAction SilentlyContinue | ForEach-Object {
        $recorded = (Get-Content $_.FullName -ErrorAction SilentlyContinue | Select-Object -First 1)
        $parts = ($recorded -split '\s+')
        if ($parts.Count -ge 3 -and $parts[0] -match '^\d+$') {
            $proc = Get-Process -Id ([int]$parts[0]) -ErrorAction SilentlyContinue
            # Identity check, not just liveness: same pid AND same image AND same start time.
            if ($proc -and $proc.ProcessName -eq $parts[1] -and "$($proc.StartTime.Ticks)" -eq $parts[2]) {
                Write-Warn "reaping stale $($_.BaseName) (pid $($parts[0])) from a previous run"
                Stop-ProcessTree ([int]$parts[0])
            } elseif ($proc) {
                Write-Warn "pid $($parts[0]) from a previous $($_.BaseName) has been recycled by another process - not killing it"
            }
        }
        Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue
    }
}

# Stop-Process does NOT kill grandchildren, and both services spawn them (rasa forks a Sanic
# worker). taskkill /T walks the tree.
#
# Run through `cmd /c` so taskkill's stderr stays inside cmd: in PowerShell 5.1, redirecting a
# native command's stderr (`2>&1`) wraps every line in a NativeCommandError and sets $? to
# $false even when the command succeeded - which turned a clean teardown into a script error.
# The existence pre-check also stops us calling taskkill for a pid an earlier tree-kill already
# took down, which is where most of that noise came from.
function Stop-ProcessTree([int]$ProcessId) {
    if ($ProcessId -le 0) { return }
    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return }
    & cmd /c "taskkill /PID $ProcessId /T /F >nul 2>&1"
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        try { Stop-Process -Id $ProcessId -Force -ErrorAction Stop } catch { }
    }
}

function Start-Service-Process($Name, $FilePath, $Arguments, $WorkingDirectory) {
    $out = Join-Path $LogDir "$Name.log"
    $err = Join-Path $LogDir "$Name.err.log"   # must differ from $out or Start-Process throws
    $proc = Start-Process -FilePath $FilePath -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err
    $pidFile = Join-Path $PidDir "$Name.pid"
    # pid + image name + start time, not the pid alone. Windows RECYCLES pids, and the case the
    # stale-reaping exists for is a reboot or a power loss - after which the recorded number
    # almost certainly belongs to something else. Reaping on the pid alone would then
    # `taskkill /T /F` an unrelated process TREE. All three must match before anything is killed.
    Set-Content -Path $pidFile -Encoding ascii `
        -Value ("{0} {1} {2}" -f $proc.Id, $proc.ProcessName, $proc.StartTime.Ticks)
    $script:Started += [pscustomobject]@{ Name = $Name; Process = $proc; PidFile = $pidFile }
    Write-Step "$Name started (pid $($proc.Id)); log -> $out"
    return $proc
}

# Polls until the URL answers 200. Any non-200 (the sidecar answers 503 while the model
# loads) and any connection error are simply "not ready yet". Fails loudly on timeout rather
# than launching the game against a service that will never come up.
function Wait-ForHealth($Name, $Url, $TimeoutSeconds, $Process) {
    Write-Step "waiting for $Name on $Url (up to ${TimeoutSeconds}s)..."
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) {
            throw "$Name exited with code $($Process.ExitCode) before becoming healthy - see $LogDir\$Name.err.log"
        }
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
            if ($r.StatusCode -eq 200) {
                Write-Step "$Name is up."
                return
            }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become healthy on $Url within ${TimeoutSeconds}s - see $LogDir\$Name.err.log"
}

function Stop-AllStarted {
    foreach ($s in $script:Started) {
        Write-Step "stopping $($s.Name) (pid $($s.Process.Id))..."
        Stop-ProcessTree $s.Process.Id
        Remove-Item $s.PidFile -Force -ErrorAction SilentlyContinue
    }
    $script:Started = @()
}

# --- preflight ---------------------------------------------------------------------------
if (-not $NoServices) {
    if (-not (Test-Path $RasaPy)) {
        throw "nlp-python\.venv-rasa is missing. Create it:`n" +
              "  py -3.10 -m venv nlp-python\.venv-rasa`n" +
              "  nlp-python\.venv-rasa\Scripts\pip install -r nlp-python\requirements-rasa.txt"
    }
    if (-not (Test-Path $SidecarPy)) {
        throw "nlp-python\.venv is missing. Create it:`n" +
              "  py -3.11 -m venv nlp-python\.venv`n" +
              "  nlp-python\.venv\Scripts\pip install -r nlp-python\requirements-llm.txt"
    }
    if (-not (Test-Path $Model)) {
        throw "No trained Rasa model at $Model. Train it (~3 min):`n" +
              "  bash nlp-python/train_rasa.sh"
    }
}
if (-not (Test-Path (Join-Path $Game 'lib\jade-4.6.0.jar'))) {
    throw "port-command-genova\lib\jade-4.6.0.jar is missing (license: not committed). " +
          "Download it from https://jade.tilab.com/ and drop it in."
}

try {
    if (-not $NoServices) {
        Stop-StaleProcesses

        # 1) Rasa, from its OWN 3.10 venv.
        $rasa = Start-Service-Process 'rasa' $RasaPy `
            @('run', '--enable-api', '--port', '5005', '--model', 'models\portcmd_nlu.tar.gz') `
            (Join-Path $Nlp 'rasa')
        # Cold start loads DIET + the featurizers; 120 s is generous on a cold page cache.
        Wait-ForHealth 'rasa' 'http://localhost:5005/status' 120 $rasa

        # 2) The Flask LLM sidecar, from the OTHER (3.11) venv, on the fast ONNX-INT4 backend.
        #    LLM_QUANT=onnx is what makes an explanation ~17 s instead of ~9 min (BUILD_LOG task
        #    13b), so the demo pins it - but ONLY when the weights are actually on this machine.
        #
        #    The weights are ~2 GB and deliberately live outside the repo, so a fresh clone does
        #    not have them. Pinning onnx unconditionally made the sidecar fail to load, /health
        #    stay 503 for the full 180 s, and the launcher then abort and kill Rasa too - on a
        #    machine whose README correctly says "the game runs fine without the LLM". That is
        #    the 20-minute-quickstart criterion, broken by a fast-path optimisation. The probe
        #    below is config.onnx_model_available(), the same single source of truth start_llm
        #    uses. Without weights the sidecar is skipped entirely and the Assistant falls back
        #    to its plain template, which is a supported mode, not a degraded one.
        $haveOnnx = $false
        if (-not $NoLlm) {
            try {
                $probe = & $SidecarPy -c "import llm_sidecar.config as c; print('yes' if c.onnx_model_available() else 'no')" 2>$null
                $haveOnnx = ("$probe".Trim() -eq 'yes')
            } catch { $haveOnnx = $false }
        }
        if ($NoLlm) {
            Write-Warn '-NoLlm: skipping the sidecar. Assistant explanations use the plain template.'
        } elseif (-not $haveOnnx) {
            Write-Warn 'no local ONNX model found - skipping the LLM sidecar. The game runs fine:'
            Write-Warn '  Assistant explanations fall back to the plain template (same figures).'
            Write-Warn '  See docs/TROUBLESHOOTING.md to install the weights and get the fast path.'
        } else {
            $env:LLM_QUANT = 'onnx'
            $sidecar = Start-Service-Process 'llm-sidecar' $SidecarPy `
                @('-m', 'llm_sidecar.server') $Nlp
            # /health answers 503 until the weights are resident (~21 s measured, ONNX-INT4).
            Wait-ForHealth 'llm-sidecar' 'http://127.0.0.1:5006/health' 180 $sidecar
        }
    } else {
        Write-Step '-NoServices: assuming Rasa and the sidecar are already running.'
    }

    # 3) The game, in the FOREGROUND - this call is what the finally block is guarding.
    $gameArgs = @('--gui')
    if ($Scenario) { $gameArgs += @('--scenario', $Scenario) }
    # -PjadePort, not -Djade.main.port: a -D on the gradlew command line lands on the Gradle
    # DAEMON, and the forked application JVM does not inherit it, so the port override would be
    # silently ignored. build.gradle.kts forwards the project property as a systemProperty.
    $gradleArgs = @('run', "--args=$($gameArgs -join ' ')")
    if ($JadePort -ne 1099) { $gradleArgs += "-PjadePort=$JadePort" }
    if ($Profile) { $gradleArgs += @('-Pjfr', "-Pjfr.seconds=$ProfileSeconds") }

    Write-Step "launching the game: gradlew $($gradleArgs -join ' ')"
    Push-Location $Game
    try {
        & (Join-Path $Game 'gradlew.bat') @gradleArgs
    } finally {
        Pop-Location
    }
} finally {
    # Runs on a normal exit, on a throw, and on Ctrl-C.
    Stop-AllStarted
    Write-Step 'all services stopped.'
}
