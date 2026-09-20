$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.json') -Raw | ConvertFrom-Json
$form = New-Object System.Windows.Forms.Form
$form.Text = 'CasaFotos | A biblioteca da família'
$form.Size = New-Object System.Drawing.Size(650,550)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [Drawing.Color]::FromArgb(247,248,244)
$form.Font = New-Object System.Drawing.Font('Segoe UI',11)
$title = New-Object System.Windows.Forms.Label
$title.Text='CasaFotos';$title.Font=New-Object System.Drawing.Font('Segoe UI',25,[Drawing.FontStyle]::Bold);$title.SetBounds(25,15,570,60);$form.Controls.Add($title)
$status = New-Object System.Windows.Forms.Label
$status.SetBounds(28,78,570,48);$form.Controls.Add($status)
$address = New-Object System.Windows.Forms.TextBox
$address.Multiline=$true;$address.ReadOnly=$true;$address.SetBounds(28,130,570,62)
$ips = Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.AddressState -eq 'Preferred' } | Select-Object -ExpandProperty IPAddress
$address.Text = (($ips | ForEach-Object { $_ + ':47831' }) -join [Environment]::NewLine)
$form.Controls.Add($address)
$hint=New-Object System.Windows.Forms.Label
$hint.Text='No telemóvel, indica o endereço da tua rede Wi-Fi mostrado acima.';$hint.SetBounds(28,200,570,45);$form.Controls.Add($hint)
$pair=New-Object System.Windows.Forms.Button
$pair.Text='Ligar telemóvel';$pair.SetBounds(28,250,230,45);$form.Controls.Add($pair)
$start=New-Object System.Windows.Forms.Button
$start.Text='Iniciar servidor';$start.SetBounds(275,250,230,45);$form.Controls.Add($start)
$pairInfo=New-Object System.Windows.Forms.Label
$pairInfo.Font=New-Object System.Drawing.Font('Consolas',14);$pairInfo.SetBounds(28,315,570,90);$form.Controls.Add($pairInfo)
$revoke=New-Object System.Windows.Forms.Button
$revoke.Text='Desligar todos os telemóveis';$revoke.SetBounds(28,420,310,38);$form.Controls.Add($revoke)
$start.Add_Click({
    Start-Process powershell.exe -Verb RunAs -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $PSScriptRoot 'start-server.ps1')+'"'))
})
$pair.Add_Click({
    if (-not (Test-Path -LiteralPath $config.data_dir)) { [Windows.Forms.MessageBox]::Show('Inicia o servidor primeiro.'); return }
    'pair' | Set-Content -LiteralPath (Join-Path $config.data_dir 'pair.request') -Encoding Ascii
    $pairInfo.Text='A gerar código de ligação...'
})
$revoke.Add_Click({
    if ([Windows.Forms.MessageBox]::Show('Desligar os telemóveis autorizados? As fotografias serão mantidas.','CasaFotos','YesNo') -eq 'Yes') {
        'revoke' | Set-Content -LiteralPath (Join-Path $config.data_dir 'revoke.request') -Encoding Ascii
        [Windows.Forms.MessageBox]::Show('Os telemóveis terão de ser ligados novamente. Nenhuma fotografia foi apagada.')
    }
})
$timer=New-Object System.Windows.Forms.Timer
$timer.Interval=1500
$timer.Add_Tick({
    try {
        $state=Get-Content -LiteralPath (Join-Path $config.state_dir 'status.json') -Raw | ConvertFrom-Json
        $now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        if (($now-$state.heartbeat) -lt 15) { $status.Text='Servidor disponível. Podes fechar este painel.';$status.ForeColor=[Drawing.Color]::DarkGreen }
        else { $status.Text='Servidor indisponível. Carrega em Iniciar servidor.';$status.ForeColor=[Drawing.Color]::DarkRed }
        $pairFile=Join-Path $config.data_dir 'pairing.json'
        if (Test-Path -LiteralPath $pairFile) {
            $info=Get-Content -LiteralPath $pairFile -Raw | ConvertFrom-Json
            if ($info.expires -gt $now) { $pairInfo.Text="Identificação: $($info.check)`r`nCódigo: $($info.code)`r`nVálido durante 10 minutos; usar uma vez." }
            else { $pairInfo.Text='Código expirado. Carrega em Ligar telemóvel.' }
        } elseif ($pairInfo.Text -like 'Identificação:*') { $pairInfo.Text='Telemóvel ligado com sucesso.' }
    } catch { $status.Text='Servidor a iniciar ou indisponível.' }
})
$timer.Start()
$form.Add_FormClosed({$timer.Stop();$timer.Dispose()})
[void]$form.ShowDialog()
