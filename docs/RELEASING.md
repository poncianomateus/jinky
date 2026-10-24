# Lançar uma versão do Jinky

## Como as atualizações chegam

| Onde | Como |
|---|---|
| **PC** | O Jinky consulta o GitHub Releases (repositório público só de instaladores) ao abrir e a cada 6 h. Botão "Atualizar para X" → baixa o instalador, confere o SHA-256 publicado junto, executa em modo silencioso (pede admin), o instalador fecha o Jinky com limpeza, atualiza e reabre na bandeja. |
| **TVs** | Cada instalador leva o app da TV da mesma versão. O Jinky do PC o serve na rede de casa (porta 47101, só rede local). A TV vê a versão no HostInfo e mostra "Atualizar o Jinky" na tela inicial; o Android pede confirmação. |

Atualizar o PC = as TVs passam a se atualizar a partir dele.

### Canal de testes (desde 0.6.7)

Toda versão nasce como **teste** (pre-release no GitHub). Só os PCs com **"Receber versões de teste"** ligado (no card "Este PC") a recebem — e as TVs desses PCs junto, porque se atualizam pelo PC. Os demais Jinky só olham `releases/latest`, que ignora pre-releases.

1. Publica como teste → testa no seu PC/TVs.
2. Deu problema? Corrige e publica outra versão de teste (ninguém mais foi afetado).
3. Tudo certo → `scripts\promote-release.ps1` transforma aquela versão em estável e ela chega a todos.

Não existe "voltar versão" num PC alheio: se um estável quebrar, a saída é publicar logo uma versão nova corrigida. Correção urgente pode ir direto para todos com `-Stable`.

### Primeira instalação numa TV

O app da TV só se atualiza pelo PC, então a **primeira** instalação é manual:

- **Pela rede de casa (mais fácil):** o card "TVs pareadas" do Jinky do PC mostra o endereço `http://<IP do PC>:47101/tv/jinky.apk`. Fire TV: app **Downloader** → digitar o endereço. Google TV (TCL etc.): ativar *Fontes desconhecidas* e abrir o endereço num navegador da TV (ou instalar o APK por pen drive).
- **Pela internet:** cada release traz `Jinky-TV.apk`; a versão estável fica sempre em `https://github.com/poncianomateus/jinky-releases/releases/latest/download/Jinky-TV.apk`.

## Passo a passo

1. Edite `VERSION` (ex.: `0.3.2`). Vale para PC, TV, motor e instalador.
2. `powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1`
   - roda os testes do protocolo, compila motor, app da TV (confere o versionCode), app do Windows (autocontido) e o instalador
   - gera `dist\Jinky-Setup-<versão>.exe`, `dist\Jinky-Setup-<versão>.exe.sha256` e `dist\Jinky-TV-<versão>.apk`
3. Commit + tag `v<versão>` no repositório do código.
4. `powershell -ExecutionPolicy Bypass -File scripts\publish-release.ps1 -NotesFile notas.md`
   - cria a release `v<versão>` **como teste** com o instalador, o `.sha256` e `Jinky-TV.apk` (`-Stable` publica direto para todos)
5. Teste no seu PC (com "Receber versões de teste" ligado) e nas TVs (ver "Teste mínimo").
6. `powershell -ExecutionPolicy Bypass -File scripts\promote-release.ps1` — libera para todos.

## ⚠️ Chave de assinatura do app da TV

O Android **só aceita uma atualização** se o APK novo for assinado com a **mesma chave** do instalado.

- Fica em `%USERPROFILE%\.jinky-signing\` (`jinky-release.keystore` + `keystore.properties` com a senha). **Fora do repositório, nunca commitar.**
- **Faça backup** desses dois arquivos (pendrive / gerenciador de senhas). Se perder, as TVs não aceitam mais atualizações: seria preciso desinstalar e reinstalar o Jinky em cada TV (e parear de novo).
- Sem esse arquivo, o build cai para a chave de debug local (serve só para testes).

## Teste mínimo antes de publicar

- [ ] Instalar por cima da versão anterior: Jinky fecha e reabre sozinho, pareamentos mantidos
- [ ] TV conecta sem aviso do firewall
- [ ] Controle Xbox funciona (driver ViGEmBus presente)
- [ ] TV mostra "Atualizar o Jinky" e atualiza
- [ ] Desinstalar: regras de firewall removidas, monitor/som normais

## Observações

- O instalador não é assinado digitalmente (certificado de código custa caro). O Windows SmartScreen pode mostrar "O Windows protegeu o computador" no primeiro download: **Mais informações → Executar assim mesmo**.
- Regras de firewall criadas: `Jinky (streaming)` UDP 47100 e `Jinky (atualizações das TVs)` TCP 47101, ambas só `localsubnet`.
