# Builds the Windows executable: target\dist\Remates\Remates.exe
# (a folder with the app and its own trimmed Java runtime; runs on PCs without Java).
# Usage, from the project root in PowerShell:   .\empaquetar.ps1
# To move it to another PC, copy (or zip) the whole target\dist\Remates folder.

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# JDK with jpackage and jmods: JAVA_HOME if set, otherwise the one on the PATH
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Split-Path (Split-Path (Get-Command java).Source) }
$jpackage = Join-Path $jdk 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { throw "No se encontro jpackage en $jdk (se necesita un JDK 21 completo)" }
if (-not (Test-Path (Join-Path $jdk 'jmods'))) { throw "El JDK en $jdk no tiene la carpeta jmods (necesaria para el runtime incluido)" }

Write-Host '1/3  Compilando target\remates.jar (app + librerias)...'
& .\mvnw.cmd -q -B package
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion con Maven' }

Write-Host '2/3  Icono desde resources\icon.svg...'
& (Join-Path $jdk 'bin\java.exe') -cp target\classes remates.ui.GenerarIcono target\remates.ico
if ($LASTEXITCODE -ne 0) { throw 'Fallo la generacion del icono' }

# jpackage copies everything in --input into the app: give it only the jar
$entrada = 'target\jpackage-input'
Remove-Item -Recurse -Force $entrada, 'target\dist\Remates' -ErrorAction SilentlyContinue
New-Item -ItemType Directory $entrada | Out-Null
Copy-Item target\remates.jar $entrada

# Java modules of the bundled runtime: what jdeps finds in remates.jar, plus what is loaded at run time
# (jdk.crypto.ec: HTTPS to Google; jdk.localedata: Spanish sorting/number formats; jdk.charsets: encodings)
$modulos = 'java.base,java.compiler,java.desktop,java.naming,java.security.jgss,java.sql,jdk.httpserver,' +
           'jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets'

Write-Host '3/3  Remates.exe con jpackage...'
& $jpackage --type app-image `
    --name Remates `
    --app-version 1.0.0 `
    --vendor Remates `
    --description 'Remates del Boletin Judicial' `
    --input $entrada `
    --main-jar remates.jar `
    --main-class Main `
    --icon target\remates.ico `
    --add-modules $modulos `
    --jlink-options '--strip-debug --no-header-files --no-man-pages --compress=zip-6' `
    --java-options '-Dfile.encoding=UTF-8' `
    --dest target\dist
if ($LASTEXITCODE -ne 0) { throw 'Fallo jpackage' }

$mb = [math]::Round(((Get-ChildItem -Recurse target\dist\Remates | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "Listo: target\dist\Remates\Remates.exe  ($mb MB en total)"
