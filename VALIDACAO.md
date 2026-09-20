# Validação e limites conhecidos

## Testes automatizados

O projeto contém oito testes funcionais do servidor e três verificações estáticas do instalador. Estes testes usam dados artificiais e não validam a experiência completa em todos os equipamentos.

O código da versão inicial foi compilado para Android com JDK 17 e SDK 35. A interface mais recente precisa de nova compilação e validação num dispositivo real.

## Observações de instalação em Windows

No dia 20 de setembro de 2026, a instalação num PC Windows criou o ficheiro VHD fixo, montou-o, criou a partição, formatou o volume e atribuiu o ponto de montagem. O painel do servidor mostrou o serviço como disponível. Um pedido local a `https://192.168.1.6:47831/hello` devolveu `{"app":"CasaFotos","version":1}`.

Estes resultados confirmam a instalação inicial nesse equipamento e a resposta HTTPS local. Não confirmam o comportamento após reinício nem a transferência ponta a ponta a partir do Android.

## Pendente

- Testar a importação e a recuperação em vários equipamentos Android.
- Confirmar o diálogo de remoção do sistema e o comportamento quando o acesso é recusado.
- Testar a recuperação após desligar e voltar a ligar o PC.
- Testar backups, restauro e atualizações de uma biblioteca que já contenha fotografias.

Não uses fotografias importantes como único material de teste. Confirma os originais e a cópia independente antes de utilizar a funcionalidade de remoção.
