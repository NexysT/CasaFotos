$ErrorActionPreference = 'Stop'
trap {
    Write-Host ('Não foi possível concluir: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Guarda esta mensagem. Não apagues um disco virtual que já contenha fotografias.'
    Read-Host 'Enter para fechar'
    exit 1
}
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $child = Start-Process powershell.exe -Verb RunAs -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"')) -Wait -PassThru
    exit $child.ExitCode
}
if (-not [Environment]::Is64BitOperatingSystem) { throw 'Esta versão requer Windows de 64 bits.' }
$appRoot = Join-Path $env:ProgramData 'CasaFotos'
if (Test-Path (Join-Path $appRoot 'config.json')) {
    Write-Host 'CasaFotos já está instalado. Os dados existentes foram preservados.'
    & (Join-Path $appRoot 'panel.ps1')
    exit
}
Add-Type -AssemblyName System.Windows.Forms
Write-Host ''
Write-Host 'CASAFOTOS | Instalação no PC' -ForegroundColor Green
Write-Host 'Escolhe uma pasta num SSD local. Dentro dela será criada CasaFotos-Arquivo.'
$picker = New-Object System.Windows.Forms.FolderBrowserDialog
$picker.Description = 'Escolhe a localização no SSD para os 30 GB do CasaFotos'
$picker.SelectedPath = [System.IO.Path]::GetPathRoot($env:SystemRoot)
if ($picker.ShowDialog() -ne 'OK') { exit }
$storageRoot = Join-Path $picker.SelectedPath 'CasaFotos-Arquivo'
if ($storageRoot -match '["\r\n]') { throw 'Escolhe uma localização sem aspas nem quebras de linha.' }
if ($storageRoot.StartsWith('\\')) { throw 'Escolhe um SSD local, não uma pasta de rede.' }
$diskRoot = [System.IO.Path]::GetPathRoot($storageRoot)
$driveInfo = New-Object System.IO.DriveInfo($diskRoot)
if ($driveInfo.DriveFormat -ne 'NTFS') { throw 'A localização tem de estar num volume NTFS.' }
Write-Host ''
Write-Host ('Localização: ' + $storageRoot)
Write-Host '1 - Reservar fisicamente cerca de 30 GB num novo disco virtual (recomendado).'
Write-Host '2 - Usar pasta com limite de 30 GB, sem reservar espaço antecipadamente.'
$choice = Read-Host 'Escolha [1]'
$fixed = ($choice -eq '' -or $choice -eq '1')
if (-not $fixed -and $choice -ne '2') { throw 'Escolha inválida.' }
# Uma tentativa anterior pode ter criado apenas a pasta e o ponto de montagem.
# Reutilizamos apenas uma pasta vazia ou contendo SOMENTE um ponto de montagem vazio.
# Qualquer VHD, ficheiro inesperado ou dados reais interrompem a instalação.
if (Test-Path -LiteralPath $storageRoot) {
    $prior = @(Get-ChildItem -LiteralPath $storageRoot -Force)
    $emptyMount = Join-Path $storageRoot 'Volume'
    $recoverable = ($prior.Count -eq 0 -or
                    ($prior.Count -eq 1 -and $prior[0].PSIsContainer -and
                     $prior[0].FullName -eq $emptyMount -and
                     @(Get-ChildItem -LiteralPath $emptyMount -Force).Count -eq 0))
    if (-not $recoverable) {
        throw ('A pasta contém ficheiros de uma tentativa anterior ou fotografias: ' + $storageRoot +
               '. Não alterei nada. Executa VERIFICAR_PC.cmd para analisar antes de voltar a instalar.')
    }
    Write-Host 'Detetada uma tentativa anterior sem ficheiros de fotografias. A pasta vazia será reutilizada.' -ForegroundColor Yellow
}
if ($fixed -and $driveInfo.AvailableFreeSpace -lt 31GB) { throw 'Precisas de pelo menos 31 GiB livres no SSD para esta instalação.' }
Write-Host 'A instalação cria um disco virtual novo (se escolhido), duas tarefas de arranque e uma regra de firewall para a rede privada local.'
Write-Host 'Não formata nem reduz partições existentes. O Windows terá de estar acordado para a app aceder aos originais.'
if ((Read-Host 'Escreve INSTALAR para continuar') -cne 'INSTALAR') { exit }

function Set-PrivateFolder([string]$Path, [bool]$ServiceWrite) {
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544',$identity.User.Value)) {
        $rule = New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]::new($sid), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    $rights = if ($ServiceWrite) { 'Modify' } else { 'ReadAndExecute' }
    $rule = New-Object Security.AccessControl.FileSystemAccessRule([Security.Principal.SecurityIdentifier]::new('S-1-5-19'), $rights, 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

New-Item -ItemType Directory -Path $appRoot -Force | Out-Null
Set-PrivateFolder $appRoot $false
New-Item -ItemType Directory -Path $storageRoot -Force | Out-Null
Set-PrivateFolder $storageRoot $false
$stateRoot = Join-Path $appRoot 'state'
New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
Set-PrivateFolder $stateRoot $true
foreach ($scriptName in @('server.py','panel.ps1','start-server.ps1','disable.ps1','backup.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $scriptName) -Destination $appRoot -Force
}
$runtime = Join-Path $appRoot 'python'
$pythonExe = Join-Path $runtime 'python.exe'
$runtimeReady = $false
if (Test-Path -LiteralPath $pythonExe) {
    # Tentativas anteriores chegaram a instalar o runtime. Evita falhar ao extrair
    # os wheels novamente sobre ficheiros existentes.
    & $pythonExe -c 'import ssl,sqlite3,cryptography,PIL'
    $runtimeReady = ($LASTEXITCODE -eq 0)
}
if (-not $runtimeReady) {
    if (Test-Path -LiteralPath $runtime) {
        Remove-Item -LiteralPath $runtime -Recurse -Force
    }
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    Expand-Archive -LiteralPath (Join-Path $PSScriptRoot 'python-windows.zip') -DestinationPath $runtime -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $packages = Join-Path $runtime 'Lib\site-packages'
    New-Item -ItemType Directory -Path $packages -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'wheels') -Filter '*.whl' | ForEach-Object {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($_.FullName, $packages)
    }
    @('python312.zip','.','Lib\site-packages','import site') | Set-Content -LiteralPath (Join-Path $runtime 'python312._pth') -Encoding Ascii
}
& $pythonExe -c 'import ssl,sqlite3,cryptography,PIL; from cryptography.hazmat.primitives.asymmetric import rsa; rsa.generate_private_key(public_exponent=65537,key_size=2048)'
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível preparar o Python incluído.' }

$volumePath = $null
$marker = $null
if ($fixed) {
    $volumePath = Join-Path $storageRoot 'CasaFotos-30GB.vhd'
    $mountPath = Join-Path $storageRoot 'Volume'
    if (Test-Path -LiteralPath $volumePath) {
        throw ('O disco virtual já existe e NÃO será formatado: ' + $volumePath +
               '. Executa VERIFICAR_PC.cmd para verificar se guarda fotografias.')
    }
    if ($volumePath.ToCharArray() | Where-Object { [int]$_ -gt 127 }) {
        throw 'Para a reserva física, escolhe um caminho apenas com letras sem acentos e números (ex.: C:\CFT). O DiskPart pode interpretar mal outros caracteres.'
    }
    if (-not (Test-Path -LiteralPath $mountPath)) { New-Item -ItemType Directory -Path $mountPath | Out-Null }
    # A versão anterior escrevia UTF-16. Para /s, usar texto ASCII simples, com CRLF,
    # um comando por linha. Apenas são criados e formatados o NOVO ficheiro VHD.
    # Nunca há select disk N, clean, delete nem qualquer operação no disco anfitrião.
    $diskScript = Join-Path $appRoot 'create-volume.txt'
    $diskLog = Join-Path $appRoot 'create-volume.log'
    $commands = @(
        ('create vdisk file="' + $volumePath + '" maximum=28610 type=fixed'),
        ('select vdisk file="' + $volumePath + '"'),
        'attach vdisk',
        'create partition primary',
        'format fs=ntfs label=CasaFotos quick',
        ('assign mount="' + $mountPath + '"'),
        'exit'
    )
    [System.IO.File]::WriteAllText($diskScript, (($commands -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    Write-Host 'A reservar os 30 GB. A saída detalhada do DiskPart será guardada no relatório.'
    $diskOutput = @(& diskpart.exe /s $diskScript 2>&1)
    $diskExit = $LASTEXITCODE
    $diskOutput | Tee-Object -FilePath $diskLog
    if ($diskExit -ne 0) {
        throw ('O DiskPart falhou (código ' + $diskExit + '). Consulta: ' + $diskLog +
               '. O VHD não será apagado nem reformulado automaticamente.')
    }
    if (-not (Test-Path -LiteralPath $volumePath)) {
        throw ('O DiskPart terminou sem criar o VHD. Consulta: ' + $diskLog)
    }
    try {
        $volume = Get-Volume -FilePath ($mountPath + '\') -ErrorAction Stop
    } catch {
        throw ('O novo VHD existe mas não ficou montado: ' + $volumePath +
               '. Consulta ' + $diskLog + '. Não apagues o VHD antes de o inspecionar.')
    }
    if ($volume.FileSystemLabel -ne 'CasaFotos') {
        throw ('O novo volume não tem a etiqueta CasaFotos. Consulta ' + $diskLog +
               '. Não foram formatados discos físicos pelo instalador.')
    }
    Set-PrivateFolder $mountPath $true
    $marker = Join-Path $mountPath 'CASAFOTOS_VOLUME.txt'
    'Volume reservado para CasaFotos 1.1' | Set-Content -LiteralPath $marker -Encoding UTF8
    $dataRoot = Join-Path $mountPath 'Dados'
} else {
    $mountPath = $null
    $dataRoot = Join-Path $storageRoot 'Dados'
}
New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
Set-PrivateFolder $dataRoot $true
$config = @{ data_dir=$dataRoot; state_dir=$stateRoot; host='0.0.0.0'; port=47831; quota_bytes=30000000000; vhd_file=$volumePath; mount_path=$mountPath; volume_marker=$marker }
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $appRoot 'config.json') -Encoding UTF8

# O servidor corre como LocalService; apenas a tarefa que monta o VHD usa SYSTEM.
$servicePrincipal = New-ScheduledTaskPrincipal -UserId 'S-1-5-19' -LogonType ServiceAccount -RunLevel Limited
$serviceAction = New-ScheduledTaskAction -Execute (Join-Path $runtime 'python.exe') -Argument ('"' + (Join-Path $appRoot 'server.py') + '" --config "' + (Join-Path $appRoot 'config.json') + '"') -WorkingDirectory $appRoot
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'CasaFotos-Servidor' -Action $serviceAction -Principal $servicePrincipal -Settings $settings -Description 'Biblioteca CasaFotos com ligação HTTPS e armazenamento privado.' -Force | Out-Null
$bootPrincipal = New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest
$bootAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $appRoot 'start-server.ps1') + '"')
$bootSettings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit (New-TimeSpan -Minutes 5) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName 'CasaFotos-Arranque' -Action $bootAction -Trigger (New-ScheduledTaskTrigger -AtStartup) -Principal $bootPrincipal -Settings $bootSettings -Force | Out-Null
New-NetFirewallRule -Name 'CasaFotos-Local-HTTPS' -DisplayName 'CasaFotos - Wi-Fi de casa' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47831 -RemoteAddress LocalSubnet -Profile Private -Program (Join-Path $runtime 'python.exe') | Out-Null
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath('Desktop')) 'CasaFotos - Painel.lnk'))
$link.TargetPath = 'powershell.exe'
$link.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $appRoot 'panel.ps1') + '"'
$link.WorkingDirectory = $appRoot
$link.Save()
& (Join-Path $appRoot 'start-server.ps1')
Write-Host 'Instalação concluída. Instala CasaFotos.apk no telemóvel e liga-o pelo painel.' -ForegroundColor Green
Write-Host 'Se a ligação falhar, confirma que a rede de casa está marcada como Privada no Windows.'
& (Join-Path $appRoot 'panel.ps1')
