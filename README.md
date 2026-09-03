# Haval Comfort Control

Controle das features de conforto do Haval, para a central multimídia.

Recorte enxuto do `haval-app-tool-multimidia`: mesmas funcionalidades de conforto,
sem o peso do resto (Frida, integração com o quadro de instrumentos, projetores,
Termux/SSH, bootstrap do Shizuku, ~100 MB de recursos).

## Funcionalidades

1. **Vidros ao trancar** — fecha todos os vidros quando o carro é trancado, estando em
   P com o motor desligado.
2. **Desconectar ao trancar** — ao trancar, encerra o receiver do Android Auto na
   central (`com.ts.androidauto.app`). Isso derruba a sessão e, com ela, o
   `LocalOnlyHotspot` que o AA sem fio usava — o framework cuida disso — **sem mexer
   nos rádios**: a central mantém Wi-Fi, internet e Bluetooth.

   O gatilho é a **tranca**, não o desligar: a central fica ligada alguns minutos depois
   de o carro desligar, e nesse tempo o Android Auto continuava conectado com o
   motorista ainda dentro.

   Desligar Bluetooth e Wi-Fi da central existe como **último recurso**, desligado por
   padrão, para o caso de a sessão insistir em voltar.
3. **Aviso de distrações** — mantém o aviso desligado, reagindo se a central o
   reativar sozinha.
4. **Volume inicial** — define o volume da multimídia a cada partida (default `10`,
   ajustável de 0 a 40). Aplicado uma vez por ciclo de ignição, então um restart do
   serviço no meio da viagem não mexe no volume que você escolheu.

Cada item tem seu interruptor na tela — todos vêm ligados.

Botão **Atualizar** no cabeçalho: consulta a Release mais recente no GitHub, baixa o
APK e abre o instalador.

Botão **Log**: mostra o log de diagnóstico na tela, com **Enviar** (sobe para o
Firebase e devolve uma URL) e **Salvar** (grava em `Android/data/<pkg>/files/diag.log`).

## Pré-requisito

O `shizuku_server` precisa estar de pé, subido pelo **haval-climate-control** (ou pelo
app-tool). Este app não sobe o server — o server é singleton e quem sobe depois mata
quem estava rodando. Sem ele, o serviço fica em loop de restart e diz o motivo no log.

## Instalação

Baixe o APK da [última Release](https://github.com/rocamoras/haval-comfort-control/releases/latest)
e instale na central. As atualizações seguintes saem pelo próprio botão do app.

## Build

```bash
./gradlew assembleDebug
```

Release exige `app/release.keystore` e as variáveis `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` e `SIGNING_KEY_PASSWORD`. Um push em `master` ou `preview` faz o
CI publicar a Release com a tag `v$versionName`.
