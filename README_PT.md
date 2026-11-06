# Eclipse Nativo PT-BR (Android)

Frontend nativo (Kotlin + Jetpack Compose) com emulação via **núcleos libretro (.so)**, sem WebView.
A interface segue a do Eclipse (biblioteca, "Continuar jogando", capas, ajustes), toda em português.

> ⚠️ Este projeto foi escrito sem poder compilar nem rodar (sem Android SDK no ambiente de criação).
> Só o código C++ teve a sintaxe verificada. Espere pequenos erros de compilação na primeira vez;
> mande a saída do Android Studio que eu corrijo.

## Como compilar
1. Instale o **Android Studio** (Koala ou mais novo) com **SDK 34**, **NDK** e **CMake** (SDK Manager > SDK Tools).
2. `File > Open` e escolha esta pasta. Aguarde o Gradle sincronizar (precisa de internet).
3. Conecte o celular (depuração USB) e clique em **Run**. Ou `Build > Build APK(s)`.

## Núcleos (obrigatório para jogar)
Baixe núcleos libretro **Android arm64-v8a** (o buildbot do libretro publica em
`buildbot.libretro.com/nightly/android/latest/arm64-v8a/`; confirme o endereço no site):

| Sistema | Núcleo |
|---|---|
| GB / GBC / GBA | `mgba_libretro_android.so` |
| NES | `fceumm_libretro_android.so` (ou `nestopia_...`) |
| SNES | `snes9x_libretro_android.so` |
| Master System / Game Gear / Mega Drive | `genesis_plus_gx_libretro_android.so` |

Duas formas de instalar:
- **No app:** Ajustes > Núcleos > *Importar núcleo (.so)* (sem recompilar).
- **No APK:** copie para `app/src/main/jniLibs/arm64-v8a/` **renomeando com prefixo `lib`**
  (ex.: `libmgba_libretro_android.so`) e recompile.

## O que já funciona
- Biblioteca com busca, ordenação (nome / data / último jogo) e "Continuar jogando".
- **Adicionar jogo só aparece ao tocar no +.**
- **Capas por link da internet, baixadas e guardadas no aparelho** (aparecem offline). Se estiver sem internet
  na hora, o link fica salvo e o app baixa quando abrir com conexão. Também aceita capa da galeria.
- Menu do jogo: jogar, capa, renomear, excluir.
- Emulação: vídeo, áudio, controles na tela (GB, GBA, NES, SNES, SMS/GG, Mega Drive), controle físico,
  save da cartucho (.srm) automático, 4 slots de save state, velocidade 1x/2x/3x.
- Sem limite de "7 dias": é app nativo, os dados ficam no armazenamento do app até você desinstalar ou limpar.

## Ainda não portado do Swift (próximas etapas)
Cheats, perfis de controle editáveis, tags, capas do OpenVGDB, núcleos com opções, ROMs dentro de .zip,
Google Drive/Dropbox, miniaturas de save state.

## Observação
O código-fonte do Eclipse (Swift) não vem com arquivo de licença no zip enviado. Este projeto é só inspirado
na interface e escrito do zero em Kotlin/C++; mantenha para uso pessoal.

## Gerar o APK sem Android Studio (GitHub Actions)
1. Crie um repositório no GitHub (pode ser privado) e envie o conteúdo desta pasta.
2. Aba **Actions** > **Build APK** > **Run workflow**.
3. Quando terminar, baixe o artefato **eclipse-nativo-apk** (contém `app-debug.apk`) e instale no celular.
