@echo off
rem Deploy the Outside relay (relay/, a Cloudflare Worker) from the newest claude/... branch, or the branch named:
rem     Deploy relay.bat                      the claude/... branch pushed most recently
rem     Deploy relay.bat claude/some-branch   that branch
rem It fetches, switches to the branch, pulls, runs the relay's tests and only then deploys. Nothing is deployed if a
rem step fails. (11/22/2026, Jace: "Whenever you need me to run a series of commands for something can you just write
rem me a script to run?")
setlocal
cd /d "%~dp0"

echo Fetching...
git fetch origin || goto :fail

set "BR=%~1"
if "%BR%"=="" for /f "delims=" %%b in ('git for-each-ref --sort=-committerdate --count=1 --format^="%%(refname:lstrip=3)" refs/remotes/origin/claude') do set "BR=%%b"
if "%BR%"=="" (
  echo No claude/... branch found on GitHub.
  goto :fail
)
echo Branch: %BR%

git checkout "%BR%" || (
  echo.
  echo Couldn't switch to %BR%: there are probably changes on this computer that aren't committed. Nothing was deployed.
  goto :fail
)
git pull --ff-only || goto :fail

cd relay
if not exist node_modules (
  echo Installing the relay's packages...
  call npm install || goto :fail
)
echo.
echo Testing the relay...
call npm test || (
  echo.
  echo The relay's tests failed, so nothing was deployed. Send Claude the lines above.
  goto :fail
)
echo.
echo Deploying...
call npm run deploy || goto :fail
echo.
echo Done: the relay is deployed from %BR%. Tell Claude.
pause
exit /b 0

:fail
echo.
echo Stopped.
pause
exit /b 1
