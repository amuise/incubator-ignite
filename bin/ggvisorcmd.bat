::  Licensed to the Apache Software Foundation (ASF) under one or more
::  contributor license agreements.  See the NOTICE file distributed with
::  this work for additional information regarding copyright ownership.
::  The ASF licenses this file to You under the Apache License, Version 2.0
::  (the "License"); you may not use this file except in compliance with
::  the License.  You may obtain a copy of the License at
::
::       http://www.apache.org/licenses/LICENSE-2.0
::
::  Unless required by applicable law or agreed to in writing, software
::  distributed under the License is distributed on an "AS IS" BASIS,
::  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
::  See the License for the specific language governing permissions and
::  limitations under the License.

::
:: Starts GridGain Visor Console.
::

@echo off

if "%OS%" == "Windows_NT"  setlocal

:: Check JAVA_HOME.
if not "%JAVA_HOME%" == "" goto checkJdk
    echo %0, ERROR: JAVA_HOME environment variable is not found.
    echo %0, ERROR: Please create JAVA_HOME variable pointing to location of JDK 1.7 or JDK 1.8.
    echo %0, ERROR: You can also download latest JDK at: http://java.sun.com/getjava
goto error_finish

:checkJdk
:: Check that JDK is where it should be.
if exist "%JAVA_HOME%\bin\java.exe" goto checkJdkVersion
    echo %0, ERROR: The JDK is not found in %JAVA_HOME%.
    echo %0, ERROR: Please modify your script so that JAVA_HOME would point to valid location of JDK.
goto error_finish

:checkJdkVersion
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr "1\.[78]\." > nul
if %ERRORLEVEL% equ 0 goto checkGridGainHome1
    echo %0, ERROR: The version of JAVA installed in %JAVA_HOME% is incorrect.
    echo %0, ERROR: Please install JDK 1.7 or 1.8.
    echo %0, ERROR: You can also download latest JDK at: http://java.sun.com/getjava
goto error_finish

:: Check GRIDGAIN_HOME.
:checkGridGainHome1
if not "%GRIDGAIN_HOME%" == "" goto checkGridGainHome2
    pushd "%~dp0"/..
    set GRIDGAIN_HOME=%CD%
    popd

:checkGridGainHome2
:: Strip double quotes from GRIDGAIN_HOME
set GRIDGAIN_HOME=%GRIDGAIN_HOME:"=%

:: remove all trailing slashes from GRIDGAIN_HOME.
if %GRIDGAIN_HOME:~-1,1% == \ goto removeTrailingSlash
if %GRIDGAIN_HOME:~-1,1% == / goto removeTrailingSlash
goto checkGridGainHome3

:removeTrailingSlash
set GRIDGAIN_HOME=%GRIDGAIN_HOME:~0,-1%
goto checkGridGainHome2

:checkGridGainHome3
if exist "%GRIDGAIN_HOME%\config" goto checkGridGainHome4
    echo %0, ERROR: GridGain installation folder is not found or GRIDGAIN_HOME environment variable is not valid.
    echo Please create GRIDGAIN_HOME environment variable pointing to location of
    echo GridGain installation folder.
    goto error_finish

:checkGridGainHome4

::
:: Set SCRIPTS_HOME - base path to scripts.
::
set SCRIPTS_HOME=%GRIDGAIN_HOME%\bin

:: Remove trailing spaces
for /l %%a in (1,1,31) do if /i "%SCRIPTS_HOME:~-1%" == " " set SCRIPTS_HOME=%SCRIPTS_HOME:~0,-1%

if /i "%SCRIPTS_HOME%\" == "%~dp0" goto run
    echo %0, WARN: GRIDGAIN_HOME environment variable may be pointing to wrong folder: %GRIDGAIN_HOME%

:run

::
:: Set GRIDGAIN_LIBS
::
call "%SCRIPTS_HOME%\include\setenv.bat"
call "%SCRIPTS_HOME%\include\target-classpath.bat" &:: Will be removed in release.
set CP=%GRIDGAIN_HOME%\bin\include\visor-common\*;%GRIDGAIN_HOME%\bin\include\visorcmd\*;%GRIDGAIN_LIBS%

::
:: Parse command line parameters.
::
call "%SCRIPTS_HOME%\include\parseargs.bat" %*
if %ERRORLEVEL% neq 0 (
    echo Arguments parsing failed
    exit /b %ERRORLEVEL%
)

::
:: Set program name.
::
set PROG_NAME=gridgain.bat
if "%OS%" == "Windows_NT" set PROG_NAME=%~nx0%

::
:: JVM options. See http://java.sun.com/javase/technologies/hotspot/vmoptions.jsp for more details.
::
:: ADD YOUR/CHANGE ADDITIONAL OPTIONS HERE
::
if "%JVM_OPTS_VISOR%" == "" set JVM_OPTS_VISOR=-Xms1g -Xmx1g -XX:MaxPermSize=128M

::
:: Uncomment to set preference to IPv4 stack.
::
:: set JVM_OPTS_VISOR=%JVM_OPTS_VISOR% -Djava.net.preferIPv4Stack=true

::
:: Assertions are disabled by default since version 3.5.
:: If you want to enable them - set 'ENABLE_ASSERTIONS' flag to '1'.
::
set ENABLE_ASSERTIONS=1

::
:: Set '-ea' options if assertions are enabled.
::
if %ENABLE_ASSERTIONS% == 1 set JVM_OPTS_VISOR=%JVM_OPTS_VISOR% -ea

::
:: Starts Visor console.
::
"%JAVA_HOME%\bin\java.exe" %JVM_OPTS_VISOR% -DGRIDGAIN_PROG_NAME="%PROG_NAME%" ^
-DGRIDGAIN_DEPLOYMENT_MODE_OVERRIDE=ISOLATED %QUIET% %JVM_XOPTS% -cp "%CP%" ^
 org.gridgain.visor.commands.VisorConsole

:error_finish

if not "%NO_PAUSE%" == "1" pause

goto :eof
