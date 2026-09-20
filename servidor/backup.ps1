$ErrorActionPreference='Stop'
$p=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Start-Process powershell.exe -Verb RunAs -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"')) -Wait; exit }
Add-Type -AssemblyName System.Windows.Forms
$config=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Raw | ConvertFrom-Json
$dialog=New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description='Escolhe uma pasta noutro disco para a cópia de segurança'
if($dialog.ShowDialog() -ne 'OK'){exit}
$destination=Join-Path $dialog.SelectedPath ('CasaFotos-Backup-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
if($destination.StartsWith($config.data_dir,[StringComparison]::OrdinalIgnoreCase)){throw 'Escolhe uma pasta fora do armazenamento da aplicação.'}
& (Join-Path $PSScriptRoot 'start-server.ps1')
Stop-ScheduledTask -TaskName 'CasaFotos-Servidor'
try {
    # Esperar pelo fim do processo antes de copiar a base de dados.
    $deadline=(Get-Date).AddSeconds(30)
    do { Start-Sleep -Milliseconds 500; $task=Get-ScheduledTask -TaskName 'CasaFotos-Servidor' } while($task.State -eq 'Running' -and (Get-Date) -lt $deadline)
    if($task.State -eq 'Running'){throw 'O servidor não parou a tempo. A cópia não foi iniciada.'}
    New-Item -ItemType Directory -Path $destination | Out-Null
    & robocopy.exe $config.data_dir $destination /E /COPY:DAT /R:1 /W:1 /XF '*.part' 'server.lock' 'pairing.json' 'pair.request' 'revoke.request'
    if($LASTEXITCODE -ge 8){throw 'A cópia falhou. Verifica o espaço do disco de destino.'}
    Write-Host ('Cópia concluída em '+$destination)
} finally { Start-ScheduledTask -TaskName 'CasaFotos-Servidor' }
Read-Host 'Enter para fechar'
