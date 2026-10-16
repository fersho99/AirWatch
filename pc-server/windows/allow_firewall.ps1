# Ejecutar UNA VEZ como Administrador (clic derecho -> "Ejecutar con PowerShell" como admin):
#   Set-ExecutionPolicy -Scope Process Bypass; .\allow_firewall.ps1
#
# Abre en el Firewall de Windows lo que AirWatch necesita, SOLO para perfil Privado
# (tu red de casa/oficina). Si tu Wi-Fi esta marcado como "Publica", cambialo a "Privada"
# en Configuracion > Red e Internet > Wi-Fi > (tu red) > Tipo de perfil de red.

param(
    [int]$TcpPort = 8765,
    [int]$UdpPort = 8766
)

$ErrorActionPreference = "Stop"
$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not (New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error "Ejecuta este script como Administrador."
}

function Reset-Rule($name) {
    Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}

Reset-Rule "AirWatch WebSocket (TCP $TcpPort)"
New-NetFirewallRule -DisplayName "AirWatch WebSocket (TCP $TcpPort)" -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort $TcpPort -Profile Private | Out-Null

Reset-Rule "AirWatch Movimiento (UDP $UdpPort)"
New-NetFirewallRule -DisplayName "AirWatch Movimiento (UDP $UdpPort)" -Direction Inbound -Action Allow `
    -Protocol UDP -LocalPort $UdpPort -Profile Private | Out-Null

# mDNS (descubrimiento automatico): consultas multicast entrantes en UDP 5353
Reset-Rule "AirWatch mDNS (UDP 5353)"
New-NetFirewallRule -DisplayName "AirWatch mDNS (UDP 5353)" -Direction Inbound -Action Allow `
    -Protocol UDP -LocalPort 5353 -Profile Private | Out-Null

Write-Host "Listo. Reglas creadas para el perfil de red Privado: TCP $TcpPort, UDP $UdpPort, UDP 5353."
Write-Host "Para quitarlas: Get-NetFirewallRule -DisplayName 'AirWatch*' | Remove-NetFirewallRule"
