# Haval Comfort Control

Controle das features de conforto do Haval, para a central multimídia.

Recorte enxuto do `haval-app-tool-multimidia`: mesmas funcionalidades de conforto,
sem o peso do resto (Frida, integração com o quadro de instrumentos, projetores,
Termux/SSH, bootstrap do Shizuku, ~100 MB de recursos).

## Funcionalidades

1. **Vidros ao rebater os retrovisores** — fecha todos os vidros quando os
   retrovisores são recolhidos. Ignorado se o carro estiver em movimento ou fora de P.
2. **Rádios ao desligar** — desliga o Bluetooth e a âncora de Wi-Fi quando o carro é
   desligado, e religa o que estava ligado assim que a central volta. Se a ROM religar
   um deles com o carro desligado, o app desliga de novo.
3. **Aviso de distrações** — mantém o aviso desligado, reagindo se a central o
   reativar sozinha.
4. **Volume inicial** — define o volume da multimídia a cada partida (default `10`,
   ajustável de 0 a 40). Aplicado uma vez por ciclo de ignição, então um restart do
   serviço no meio da viagem não mexe no volume que você escolheu.

Cada item tem seu interruptor na tela — todos vêm ligados.

Botão **Atualizar** no cabeçalho: consulta a Release mais recente no GitHub, baixa o
APK e abre o instalador.

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
