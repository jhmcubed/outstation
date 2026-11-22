@echo off
rem Start RuneLite (with -ea, as the Hub's rules want for a test) on an exact build of the Hub's Outside plugin, the
rem checkout of jhmcubed/outside-plugin beside this folder (..\outside-plugin):
rem     Try the plugin.bat 84227d0     that commit, as Claude names it
rem     Try the plugin.bat             the newest claude/... branch
rem Afterwards, send Claude %USERPROFILE%\.runelite\logs\client.log. (11/22/2026)
setlocal
cd /d "%~dp0..\outside-plugin" || (
  echo There's no outside-plugin folder beside this one.
  goto :fail
)
echo Fetching...
git fetch origin || goto :fail

set "AT=%~1"
if "%AT%"=="" for /f "delims=" %%b in ('git for-each-ref --sort=-committerdate --count=1 --format^="%%(refname:lstrip=2)" refs/remotes/origin/claude') do set "AT=%%b"
if "%AT%"=="" (
  echo Name the commit to try, e.g.  Try the plugin.bat 84227d0
  goto :fail
)
echo Build: %AT%
git checkout --detach "%AT%" || (
  echo Couldn't check out %AT%: there are probably changes in outside-plugin that aren't committed.
  goto :fail
)
git log --oneline -1
echo.
echo Starting RuneLite. When you're done, close it and send Claude:
echo     %USERPROFILE%\.runelite\logs\client.log
py run.py client
pause
exit /b 0

:fail
echo.
echo Stopped.
pause
exit /b 1
