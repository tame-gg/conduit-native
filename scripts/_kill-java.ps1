Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  if ($_.CommandLine -match 'conduit|minecraft-server|server\.jar|ItemGameplayProbe|gg\.tame\.conduit') {
    Write-Host ("kill " + $_.ProcessId)
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
  }
}
Start-Sleep 2
Write-Host done
