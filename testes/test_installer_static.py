"""Verificações estáticas do instalador Windows; os testes não executam o DiskPart."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class InstallerSafetyTests(unittest.TestCase):
    def test_diskpart_uses_ascii_and_collects_log(self):
        code = (ROOT / 'servidor/install.ps1').read_text(encoding='utf-8-sig')
        self.assertIn('[System.Text.Encoding]::ASCII', code)
        self.assertIn('create-volume.log', code)
        self.assertIn('($commands -join "`r`n")', code)

    def test_does_not_format_existing_vhd(self):
        code = (ROOT / 'servidor/install.ps1').read_text(encoding='utf-8-sig')
        self.assertIn('if (Test-Path -LiteralPath $volumePath)', code)
        self.assertIn('if (-not $recoverable)', code)
        self.assertNotIn('select disk ', code.lower().replace('select disk n', ''))
        self.assertNotIn('clean all', code.lower())

    def test_startup_never_formats_or_creates_disks(self):
        code = (ROOT / 'servidor/start-server.ps1').read_text(encoding='utf-8-sig')
        self.assertIn('Mount-DiskImage -ImagePath $config.vhd_file', code)
        self.assertIn('Add-PartitionAccessPath', code)
        self.assertNotIn('format fs=', code.lower())
        self.assertNotIn('create vdisk', code.lower())


if __name__ == '__main__':
    unittest.main()
