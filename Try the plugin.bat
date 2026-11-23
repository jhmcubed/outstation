@echo off
rem Start RuneLite (with -ea, as the Hub's rules want for a test) on an exact build of the Hub's Outside plugin, the
rem checkout of jhmcubed/outside-plugin beside this folder (..\outside-plugin):
rem     Try the plugin.bat 84227d0     that commit, as Claude names it
rem     Try the plugin.bat             (double-clicked) it asks which: the commit Claude named, or Enter for the newest
rem                                    claude/... branch (11/23/2026: double-clicked it took the newest branch without
rem                                    asking, and Jace tried update 8 when the memory fix's own build was meant)
rem Afterwards, send Claude %USERPROFILE%\.runelite\logs\client.log. (11/22/2026)
setlocal
cd /d "%~dp0..\outside-plugin" || (
  echo There's no outside-plugin folder beside this one.
  goto :fail
)
echo Fetching...
git fetch origin || goto :fail

set "AT=%~1"
set "NEWEST="
for /f "delims=" %%b in ('git for-each-ref --sort=-committerdate --count=1 --format^="%%(refname:lstrip=2)" refs/remotes/origin/claude') do set "NEWEST=%%b"
if "%AT%"=="" (
  echo.
  echo Which build? Type the commit Claude named ^(e.g. f39ae7f^) and press Enter,
  echo or just press Enter for the newest: %NEWEST%
  set /p "AT=Build: "
)
if "%AT%"=="" set "AT=%NEWEST%"
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
