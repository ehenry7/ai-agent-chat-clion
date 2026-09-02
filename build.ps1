param([switch]$Run)
if (!(Test-Path "gradlew.bat")) {
    Write-Host "Initializing Gradle Wrapper..."
    gradle wrapper
}
if ($Run) {
    .\gradlew.bat runIde
} else {
    .\gradlew.bat buildPlugin
}
