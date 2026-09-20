# Contribuir

Obrigado pelo interesse no CasaFotos. As áreas em que mais valorizo ajuda são a seleção visual de fotografias Android, o diagnóstico de rede, a segurança e a acessibilidade.

## Comunicar erros

Abre uma issue com a versão do Android, a versão do Windows, os passos para reproduzir, o resultado observado e o comportamento esperado. Não anexes fotografias pessoais, IPs públicos, códigos de emparelhamento, certificados privados, passwords ou ficheiros VHD.

## Propor melhorias

Explica o problema que pretendes resolver, os ficheiros afetados e os testes que executaste. Mantém as mensagens dirigidas ao utilizador em português europeu, com nomes e comentários claros. Não apresentes funcionalidades como validadas sem um teste reproduzível.

Antes de submeter alterações, executa os testes disponíveis:

```powershell
python -m pip install -r servidor/requirements.txt
python -m unittest discover -s testes -v
```
