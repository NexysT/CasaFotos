$ErrorActionPreference='Stop'
$p=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Start-Process powershell.exe -Verb RunAs -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"')) -Wait; exit }
if ((Read-Host 'Escreve DESATIVAR para parar o servidor e retirar o arranque automático, mantendo todas as fotografias') -cne 'DESATIVAR') { exit }
foreach($task in @('CasaFotos-Servidor','CasaFotos-Arranque')) { Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue; Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue }
Remove-NetFirewallRule -Name 'CasaFotos-Local-HTTPS' -ErrorAction SilentlyContinue
Write-Host 'Servidor desativado. As fotografias, o disco virtual e o código foram mantidos.'
Read-Host 'Enter para fechar'
