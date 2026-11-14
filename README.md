# Series Organizer

Aplicação Android para normalizar nomes de episódios e legendas numa única pasta.

## Regras da primeira versão
- Seleção de uma pasta através do seletor de documentos Android.
- Deteção de padrões `S01E01`, `1x01` e `Season 1 Episode 01`.
- Ordenação pela temporada e número do episódio encontrados no nome.
- Normalização para `S01E01.ext`.
- Legendas associadas pelo identificador de temporada/episódio; sufixos de idioma reconhecidos são preservados (por exemplo `S01E01.pt.srt`).
- Pré-visualização e verificação de conflitos antes de renomear.
- Sem acesso à internet em execução.

## Compilação
O GitHub Actions compila o APK de depuração em cada push para `main`. O ficheiro fica disponível nos artefactos da execução.
