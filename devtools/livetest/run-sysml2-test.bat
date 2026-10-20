@echo off
rem Runs the Cameo MCP SysML v2 live test. Open a SysML v2 project in Cameo 2026x first.
cd /d "%~dp0"
set JAVA="C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
if not exist %JAVA% set JAVA=java
%JAVA% -cp "..\plugins\com.cameomcp.plugin\cameo-mcp-stdio.jar" LiveTest.java . scenario-sysml2.json > run-sysml2.log 2>&1
echo done > done-sysml2.flag
