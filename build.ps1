param([switch]$Install)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$savedJava = $env:JAVA_HOME
$savedTemp = $env:TEMP
$savedTmp = $env:TMP
$savedJavaOpts = $env:JAVA_OPTS
try {
    $studioJava = 'C:\Program Files\Android\Android Studio\jbr'
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $studioJava)) { $env:JAVA_HOME = $studioJava }
    # Short paths avoid Windows Unix-domain socket failures in some JDK installations.
    $taskTemp = Join-Path $PSScriptRoot '.tools\tmp'
    New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null
    $env:TEMP = $taskTemp
    $env:TMP = $taskTemp
    $env:JAVA_OPTS = "$savedJavaOpts `"-Djava.io.tmpdir=$taskTemp`" `"-Djdk.net.unixdomain.tmpdir=$taskTemp`""
    $tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
    if ($Install) { $tasks += ':app:installDebug' }
    & .\gradlew.bat @tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Android build failed with exit code $LASTEXITCODE" }
    Write-Host "APK: $(Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk')"
} finally {
    $env:JAVA_HOME = $savedJava
    $env:TEMP = $savedTemp
    $env:TMP = $savedTmp
    $env:JAVA_OPTS = $savedJavaOpts
    Pop-Location
}
