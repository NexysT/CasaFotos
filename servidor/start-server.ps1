$ErrorActionPreference = 'Stop'
$config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Raw | ConvertFrom-Json
if ($config.vhd_file) {
    if (-not (Test-Path -LiteralPath $config.vhd_file)) {
        throw 'O disco virtual não foi encontrado. Os dados não foram recriados.'
    }
    if (-not (Test-Path -LiteralPath $config.volume_marker)) {
        # Montagem sem formatação e sem reutilizar o ficheiro temporário de instalação.
        # Get-DiskImage identifica especificamente o VHD da configuração, não o SSD.
        $image = Get-DiskImage -ImagePath $config.vhd_file -ErrorAction Stop
        if (-not $image.Attached) {
            Mount-DiskImage -ImagePath $config.vhd_file -NoDriveLetter -ErrorAction Stop | Out-Null
        }
        $disk = Get-DiskImage -ImagePath $config.vhd_file | Get-Disk -ErrorAction Stop
        $parts = @(Get-Partition -DiskNumber $disk.Number -ErrorAction Stop)
        if ($parts.Count -ne 1) {
            throw 'O disco virtual tem uma estrutura inesperada. Não será formatado nem alterado.'
        }
        $vol = $parts[0] | Get-Volume -ErrorAction Stop
        if ($vol.FileSystemLabel -ne 'CasaFotos' -or $vol.FileSystem -ne 'NTFS') {
            throw 'O volume do VHD não corresponde ao CasaFotos. O servidor não foi iniciado.'
        }
        if (-not (Test-Path -LiteralPath $config.mount_path)) {
            New-Item -ItemType Directory -Path $config.mount_path -Force | Out-Null
        }
        Add-PartitionAccessPath -DiskNumber $disk.Number -PartitionNumber $parts[0].PartitionNumber -AccessPath $config.mount_path -ErrorAction Stop
        if (-not (Test-Path -LiteralPath $config.volume_marker)) {
            throw 'O volume reservado não ficou disponível. O servidor não foi iniciado.'
        }
    }
}
Start-ScheduledTask -TaskName 'CasaFotos-Servidor'
