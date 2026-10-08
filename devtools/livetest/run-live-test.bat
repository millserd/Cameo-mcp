@echo off
rem Runs the Cameo MCP live test against a running Cameo (MCP server on port 18740).
cd /d "%~dp0"
set JAVA="C:\Program Files\Java\jdk-21.0.10\bin\java.exe"
if not exist %JAVA% set JAVA=java
%JAVA% -cp "..\plugins\com.cameomcp.plugin\lib\gson-2.10.1.jar" LiveTest.java . > run.log 2>&1
%JAVA% -jar "..\plugins\com.cameomcp.plugin\cameo-mcp-stdio.jar" --diagnose > diagnose.log 2>&1
echo done > done.flag
