# Hace que el servidor AirWatch arranque solo al iniciar sesion en Windows (y lo quita).
#   Instalar : .\autostart.ps1
#   Quitar   : .\autostart.ps1 -Remove
# No necesita ser Administrador (la tarea es del usuario actual). Antes ejecuta una vez ..\run_server.bat para
# crear el entorno (.venv) con las dependencias, y windows\allow_firewall.ps1 como Administrador.
# El servidor corre sin ventana (pythonw); para ver mensajes ejecutalo con ..\run_server.bat.

param([switch]$Remove)

$ErrorActionPreference = "Stop"
$TaskName = "AirWatch servidor"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if ($Remove) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Host "Tarea '$TaskName' eliminada."
    return
}

$pythonw = Join-Path $Root ".venv\Scripts\pythonw.exe"
if (-not (Test-Path $pythonw)) {
    Write-Error "No existe $pythonw. Ejecuta primero run_server.bat una vez para crear el entorno."
}

$action = New-ScheduledTaskAction -Execute $pythonw -Argument "-m airwatch_server" -WorkingDirectory $Root
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal | Out-Null
Write-Host "Listo: el servidor arrancara al iniciar sesion. Para probarlo ya: Start-ScheduledTask -TaskName '$TaskName'"
Write-Host "Para detenerlo: Stop-ScheduledTask -TaskName '$TaskName'.  Para quitar el arranque: .\autostart.ps1 -Remove"
