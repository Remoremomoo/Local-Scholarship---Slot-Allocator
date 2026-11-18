@echo off
REM Compile and start the scholarship website + Java allocation engine.
REM Then open http://localhost:8080 in your browser.
cd /d "%~dp0"
javac -d . *.java || goto :error
java -cp . Scholarship_Allocator.ApiServer %1
goto :eof

:error
echo.
echo Compile failed. Check the error above.
pause
