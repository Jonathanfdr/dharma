# ☸ Dharma — meditação com sino de tigela

App de meditação no estilo de um centro budista, em duas versões:

- **Android** (nativo, Kotlin) — funciona com a **tela bloqueada**. APK pronto em [`dist/Dharma.apk`](dist/Dharma.apk).
- **Web** — para testar no computador (`web/`).

## Como funciona

Você escolhe **quantas sessões** e **quantos minutos** cada uma. Não há intervalo entre elas:

```
🔔 → 15 min → 🔔 → 15 min → 🔔 → 15 min → 🔔      (3 sessões de 15 min)
```

- O sino (tigela tibetana) toca no início, entre as sessões e no final.
- **Música de fundo** e **efeitos** começam *depois* do primeiro sino (entram em fade-in) e terminam em **fade-out antes do último sino**.
- Músicas (Templo, Himalaia, Lótus, Om) e efeitos (chuva, vento, sinos de vento, riacho, ondas, grilos, fogueira) podem ser combinados, cada um com volume próprio, ajustáveis durante a sessão.
- Os loops são **sem emenda**: todos os áudios são sintetizados por `tools/generate_audio.py` de forma matematicamente periódica (o fim emenda exatamente no começo), então não dá para perceber o loop.
- Pausar/retomar e encerrar (com fade rápido). No Android, uma chamada telefônica ou desconectar os fones pausa automaticamente.

## Instalar o APK no celular

1. Copie `dist/Dharma.apk` para o celular (cabo, Drive, Telegram…) ou, com o celular em modo depuração USB:
   ```bash
   adb install -r dist/Dharma.apk
   ```
2. Abra o arquivo no celular e permita "instalar apps de fontes desconhecidas" quando pedido.
3. Aceite a permissão de notificações (opcional, mas recomendada: mostra o controle na tela de bloqueio).
4. Escolha as sessões, toque em **Começar meditação** e pode bloquear a tela.

**Como funciona com a tela bloqueada:** o áudio roda num *Foreground Service* (tipo mediaPlayback) com *WakeLock*, e uma notificação com contagem regressiva e botões Pausar/Encerrar. Em alguns celulares (Xiaomi, Samsung, Huawei…) o sistema é agressivo com bateria; se a sessão cortar, desative a otimização de bateria para o Dharma em *Configurações → Apps → Dharma → Bateria → Sem restrições*.

> O APK de release é assinado com uma chave que fica fora do repositório (GitHub Secrets `KEYSTORE_BASE64` e `KEYSTORE_PASSWORD`). Compilando localmente sem elas, usa a chave de debug do Android (também instala normalmente).

## Release automático (GitHub Actions)

O workflow `.github/workflows/release.yml` compila o APK e o publica como **Release** do GitHub sempre que você envia uma tag `v*`:

```bash
git tag v1.0
git push origin v1.0
```

O APK `Dharma-1.0.apk` aparece na página de Releases do repositório (a versão vem da tag). Também dá para rodar o workflow manualmente na aba *Actions*; nesse caso o APK fica como artefato da execução.

## Rodar a versão web

O navegador precisa de um servidor local para carregar os áudios (não abra o `index.html` com duplo clique):

```bash
cd web
python3 -m http.server 8000
```

Abra <http://localhost:8000>. As configurações ficam salvas no navegador.

## Compilar o APK você mesmo

Requisitos: JDK 17+ e Android SDK (platform 34, build-tools 34.0.0).

```bash
cd android
echo "sdk.dir=$HOME/Android/sdk" > local.properties
./gradlew assembleRelease
# saída: android/app/build/outputs/apk/release/app-release.apk
```

## Regerar os áudios

```bash
python3 -m venv .venv && .venv/bin/pip install numpy
.venv/bin/python tools/generate_audio.py
```

Grava em `web/audio/` e `android/app/src/main/assets/audio/`. Para trocar o sino ou adicionar sons, edite o script (ou substitua os `.wav` mono 44,1 kHz 16 bits; para loops sem emenda, o arquivo precisa ser periódico).

## Estrutura

```
dist/Dharma.apk            APK pronto
web/                       versão web (Web Audio API)
android/                   projeto Android (Kotlin)
  .../AudioEngine.kt       mixer: sinos, loops, fades (relógio por contagem de amostras)
  .../MeditationService.kt serviço em primeiro plano, WakeLock, foco de áudio, notificação
  .../MainActivity.kt      interface
tools/generate_audio.py    síntese dos sons
```
