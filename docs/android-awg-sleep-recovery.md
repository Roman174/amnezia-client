# Android: восстановление AmneziaWG после сна

Изменения относятся к Android WireGuard и AmneziaWG 3.1. Формат конфигурации,
серверные параметры, enum состояний и реализации других платформ не меняются.

## Диагностика

Исходная ревизия клиента: `ac175c8a`. Строки в этой таблице относятся к ней.

| Наблюдение | Исходник | Вывод |
|---|---|---|
| Screen-on только включает статистику; receiver зависит от уведомлений | `client/android/src/org/amnezia/vpn/AmneziaVpnService.kt:336` | Подтверждено; пробуждение без смены сети не запускает диагностику |
| Lost/blocked только пишутся в лог, validation той же сети не сбрасывается | `client/android/utils/src/main/kotlin/net/NetworkState.kt:53` | Подтверждено; потеря/восстановление физического пути может остаться незамеченной |
| Reconnect разрешён только из CONNECTED | `client/android/src/org/amnezia/vpn/AmneziaVpnService.kt:563` | Подтверждено; повторная попытка после зависшего reconnect отсутствует |
| Connect допускается во время RECONNECTING | `client/android/src/org/amnezia/vpn/AmneziaVpnService.kt:495` | Подтверждено; новая работа может перезаписать ссылку на ещё работающую задачу |
| Ожидание первого handshake не ограничено | `client/android/wireguard/src/main/kotlin/org/amnezia/vpn/protocol/wireguard/Wireguard.kt:194` | Подтверждено; CONNECTING/RECONNECTING могут не завершиться |
| Ошибка получения telemetry `-2` означает CONNECTED | тот же файл, строка 204 | Подтверждено; UI показывает успех без доказательства handshake |
| После первого handshake монитор завершается | тот же файл, строки 194–208 | Подтверждено; последующее зависание не обнаруживается |
| Timer.duration записывается после отпускания modifyingLock | `amneziawg-go` v3.1.20260814, `device/timers.go:41` | Воспроизведено под race detector; конкурентный Mod может потерять установленное состояние |
| Go suspend/resume | Android wrapper `tunnel/tools/libwg-go/Makefile` и `go.mod` | Патч CLOCK_BOOTTIME применяется к Go 1.24.2, но модуль требует 1.25.0; автоматическая смена toolchain обходит патч |

Одна общая причина на Samsung Galaxy S25 Ultra не доказана: устройство не было
подключено. Доказаны независимые дефекты, способные вызвать или оставить без
восстановления описанное состояние. Сообщения пользователей согласуются с ними:
[3304](https://github.com/amnezia-vpn/amnezia-client/issues/3304),
[3255](https://github.com/amnezia-vpn/amnezia-client/issues/3255),
[3403](https://github.com/amnezia-vpn/amnezia-client/issues/3403).

## Реализация

- Один `TunnelRecovery` на сессию выполняет start, диагностику и restart.
  События сети/питания обновляют StateFlow, а не создают конкурирующие restart jobs.
  Пользовательский disconnect отменяет сессию и дожидается её очистки.
- Пассивный NetworkCallback наблюдает физические INTERNET/NOT_VPN сети.
  VALIDATED повышает приоритет, но не блокирует соединения через сети с
  недоступной системной проверкой Интернета. VPN transport исключён явно.
- После Doze есть 5 секунд на поступление события разблокировки сети.
  Пробуждение на той же физической сети само по себе не пересоздаёт backend.
- Telemetry содержит handshake с миллисекундной точностью, RX/TX, исходящий
  пользовательский трафик до шифрования (`tun_tx_bytes`) и ошибки UDP send.
  Последний счётчик спроса исключает keepalive и поддерживает app split tunneling:
  он считает именно пакеты, прошедшие в TUN и сопоставленные peer.
- Старый handshake без исходящего трафика не считается сбоем. После 30 секунд
  отсутствия прогресса при исходящем трафике выполняется диагностический handshake.
  Только отсутствие ответа или устойчиво недоступная telemetry разрешает restart.
  Отложенный из-за RekeyTimeout probe не считается успешным ответом.
- Пассивная выборка выполняется раз в 15 секунд при включённом экране и в
  ограниченном 60-секундном окне после событий питания. В Doze выборок нет.
  Опрос раз в секунду применяется только к ограниченному ожиданию handshake.
  Wake lock, ICMP и внешние HTTP health endpoints не используются.
- Каждое ожидание соединения ограничено 45 секундами; максимум пять попыток,
  backoff 2/4/8/16 секунд, минимум 2 секунды между стартами backend.
  После исчерпания попыток сессия завершается с ошибкой и DISCONNECTED.
- Native вызовы защищены одним lock. При restart сохраняется исходный TUN FD,
  backend получает дубликат. Маршруты, DNS, фильтры приложений и исходная
  конфигурация не пересоздаются. При окончательном stop закрывается и исходный FD.
- Системный Always-on startup и сохранённая конфигурация сохранены.
  Во время retry удерживаемый TUN препятствует выходу выбранного трафика мимо VPN.
  После окончательного завершения или смерти процесса запрет такого выхода
  обеспечивает Android «Блокировать подключения без VPN»; Always-on сам по себе
  такой гарантии не даёт.

Android source закреплён на `5c16489e2cd9ed3a0a7a27c7445bba5238132f86`,
Go source — `1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1`.
Патчи хранятся в `recipes/awg-android/patches`, применяются Conan к чистым
исходникам. Внешняя локально модифицированная библиотека для сборки не нужна.
Go 1.25.0 закреплён с проверкой checksum, CLOCK_BOOTTIME применяется к реально
используемому runtime, `GOTOOLCHAIN=local` запрещает незаметную подмену.

[PR #205](https://github.com/amnezia-vpn/amneziawg-go/pull/205) изучен;
исправление доступа под lock сохранено. Дополнительно pending отделён от duration:
`Mod(0)` должен сработать, а не выглядеть как отменённый таймер. Это покрыто тестом.

## Автоматизированная проверка

Без Qt и Android-устройства:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
client/android/gradlew -p client/android/recovery-tests test \
  -Pkotlin.compiler.execution.strategy=in-process
```

Тесты компилируют production `TunnelRecovery`, `TunnelHealth`,
`PhysicalNetworkTracker` и `ProtocolState`; native backend заменён управляемым fake.
18 тестов прошли. Проверены 10 минут/час виртуального сна, wake до unblock,
простой, односторонняя активность, успешный/отложенный/неуспешный probe,
переход сети, потеря сигнала, coalescing callbacks, исчерпание попыток,
ошибка telemetry, отмена пользователем и сброс счётчиков backend.
Это проверка алгоритма, а не доказательство поведения Android framework.

В полученном и пропатченном Conan Go source:

```bash
go test -race ./device \
  -run 'TestTimer(ExpiryRace|ZeroDurationAndRearm)|TestRecoveryProbeAndTelemetry' \
  -count=10
go test -race ./device -count=1
```

Исходный timer с regression tests падает на DATA RACE и `Mod(0)`.
Полный device suite выявляет `TestAWGDevicePing`; этот же тест падает на
неизменённой закреплённой версии. Остальные device tests прошли.
Не объявлять полный suite успешным и не маскировать этот upstream сбой.

Android `utils`, `wireguard`, `awg` компилируются. Чистые Conan source и CMake
configure/build для arm64-v8a выполнены; собраны `libwg-go.so`, `libwg.so`,
`libwg-quick.so`, проверен экспорт нового JNI probe.

## Проверка на устройстве

Запустить `adb devices -l` и выбрать serial. Требуется установленная сборка,
подключённый AWG, включённое сохранение логов и разблокируемое тестовое устройство.
Сценарий временно эмулирует отключённую зарядку и принудительный Doze; в finally
восстанавливает battery/deviceidle. Root не требуется.

```bash
python3 deploy/tests/android-awg-sleep-recovery.py --serial "$SERIAL" --sleep-seconds 600
python3 deploy/tests/android-awg-sleep-recovery.py --serial "$SERIAL" --sleep-seconds 3600
python3 deploy/tests/android-awg-sleep-recovery.py --serial "$SERIAL" \
  --sleep-seconds 600 --traffic-package "$BROWSER_PACKAGE" --url "$TEST_URL"
```

Без traffic-package сценарий проверяет отсутствие лишнего RECONNECTING после сна.
С ним проверяется authenticated handshake/RX progress за 120 секунд после wake.
Использовать некэшируемый URL своего тестового ресурса. При app split tunneling
браузер должен входить в список приложений через VPN; adb shell ping не является
валидной заменой трафика выбранного приложения. Скрипт сохраняет только состояния
и численные счётчики; полный конфиг и ключи не читаются.

Повторить для split tunneling выключен / include / exclude и для Always-on
выключен / включён с «Блокировать подключения без VPN». Проверить адресные
include/exclude маршруты отдельно. Это покрытие сценариев 13–14 на устройстве,
которое нельзя заменить fake-тестом.

Дополнительные проверки:

1. Wi-Fi → mobile и обратно: `adb -s "$SERIAL" shell svc wifi disable`, затем
   `enable`; убедиться, что mobile data доступны. Исходное состояние Wi-Fi восстановить.
2. Потеря/возврат сигнала: переключить авиарежим через UI; проверить bounded retry
   и последующее CONNECTED, если сеть вернулась до исчерпания попыток.
3. UDP blackhole: на отдельном тестовом Wi-Fi/router временно блокировать только
   ответы тестового AWG endpoint, сохраняя остальной доступ. Проверить probe,
   backoff и отсутствие параллельных restart. На телефоне без root надёжно
   внедрить такое состояние через adb нельзя.
4. Во время probe/backoff нажать Disconnect в UI или уведомлении. После новых
   screen/network callbacks соединение не должно самопроизвольно появиться.
5. Повторить с выключенными уведомлениями, закрытой Activity и с native импортом,
   self-hosted конфигурацией, Premium/Free конфигурацией при наличии доступа.
6. Для натурального сна повторить без `force-idle`; принудительный Doze не
   воспроизводит все ограничения One UI и аппаратный suspend.

Числовой `handshakeMillis` доступен в отфильтрованной строке `Tunnel health`:

```bash
adb -s "$SERIAL" logcat -v brief AmneziaVpnService:D '*:S'
adb -s "$SERIAL" shell dumpsys deviceidle
```

## Оставшиеся ограничения

Нет подключённого Android-устройства; Samsung/Android 17, реальный Doze,
split tunneling, Always-on/lockdown и process death в runtime не проверены.
Windows-host build патченного runtime не запускался. Coroutine timeout ограничивает
ожидание handshake и retry, но не может принудительно прервать зависший JNI syscall.
Native hang вне штатного поведения требует отдельной диагностики процесса.

Полная APK-сборка и подпись фиксируются отдельно в итоговом сообщении вместе с
артефактом: наличие native `.so` или успешных JVM-тестов не означает готовность APK.
