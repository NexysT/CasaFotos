$ErrorActionPreference = 'Continue'
Write-Host 'CASAFOTOS | Diagnostico sem alteracoes ao PC' -ForegroundColor Cyan
Write-Host 'Nao apagues nem formates ficheiros VHD para repetir a instalacao.'
$paths = @('C:\CFT\CasaFotos-Arquivo', (Join-Path $env:ProgramData 'CasaFotos'))
foreach ($path in $paths) {
    Write-Host ("`nLocal: " + $path)
    if (Test-Path -LiteralPath $path) {
        Get-ChildItem -LiteralPath $path -Force | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
    } else { Write-Host 'Nao existe.' }
}
$log = Join-Path $env:ProgramData 'CasaFotos\create-volume.log'
if (Test-Path -LiteralPath $log) {
    Write-Host ("`nRelatorio DiskPart: " + $log)
    Get-Content -LiteralPath $log -Tail 100
} else { Write-Host "`nO relatorio do DiskPart ainda nao existe." }
$cfg = Join-Path $env:ProgramData 'CasaFotos\config.json'
if (Test-Path -LiteralPath $cfg) {
    Write-Host "`nConfiguracao do armazenamento (sem revelar credenciais):"
    $c = Get-Content -LiteralPath $cfg -Raw | ConvertFrom-Json
    Write-Host ('Dados: ' + $c.data_dir)
    Write-Host ('VHD: ' + $c.vhd_file)
    Write-Host ('Ponto de montagem: ' + $c.mount_path)
}
Write-Host "`nEste diagnostico nao monta, apaga ou formata volumes." -ForegroundColor Green
Read-Host 'Enter para fechar'
