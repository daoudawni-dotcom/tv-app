$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (!(Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = "$env:ProgramFiles\Android\Android Studio\jbr"
    if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
    else { throw 'Install Android Studio or JDK 17 first.' }
}
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (!(Test-Path "$env:ANDROID_HOME\platforms\android-35\android.jar")) { throw 'Open Android Studio SDK Manager and install Android SDK Platform 35 first.' }
$cache = Join-Path $env:LOCALAPPDATA 'AudioTV-build'
New-Item -ItemType Directory -Force $cache | Out-Null
$gradle = Join-Path $cache 'gradle-8.9\bin\gradle.bat'
if (!(Test-Path $gradle)) {
    $zip = Join-Path $cache 'gradle-8.9-bin.zip'
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile $zip
    Expand-Archive $zip -DestinationPath $cache -Force
}
& $gradle --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Build failed. See output above.' }
Copy-Item 'app\build\outputs\apk\debug\app-debug.apk' 'AudioTV-v02-test.apk' -Force
Write-Host 'APK ready: AudioTV-v02-test.apk' -ForegroundColor Green
