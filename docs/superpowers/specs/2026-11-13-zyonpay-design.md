# ZyonPay — diseño

Plugin de economía multi-servidor para Paper 26.3 (Java 25). Proveedor de Vault (`Economy`),
MariaDB como fuente de verdad, Redis para sincronizar servidores y para el baltop.

## Principios
- Dinero como `long` en céntimos (2 decimales fijos). Nunca `double`.
- Vault es síncrono y se llama desde el hilo principal: saldos de jugadores online solo tocan caché.
- Toda escritura a MariaDB va por un único executor de un hilo (`storage`): serializa flush, refresh y unload.
- Deltas conmutativos: `balance = GREATEST(balance + ?, 0)`. Nunca se escribe un saldo absoluto salvo `set` offline.
- Cualquier fallo de BD en login = el jugador no entra (evita crear dinero o perderlo).

## Modelo de propiedad
El servidor donde el jugador está online es su dueño y tiene su `Account` en caché.
- Operaciones locales: caché + delta pendiente + cola de transacciones → flush cada `flush-interval-ticks` (defecto 20).
- Flush también al salir (`PlayerQuitEvent`) y en `onDisable`. Tras el flush de salida se publica `REFRESH`.
- Cuenta no cacheada (offline o en otro servidor): `UPDATE` atómico directo en BD (`WHERE balance >= ?` al retirar,
  tope `max-balance` al ingresar) + `REFRESH`/`PAID` por Redis.
- Al recibir `REFRESH` un dueño hace flush de esa cuenta, relee y fija `balance = db + pending`.
- Ventana conocida: retirada remota contra jugador online en otro servidor puede cruzarse con retiros locales sin volcar
  (≤ intervalo de flush); el `GREATEST(…,0)` evita saldos negativos.
- Pérdida máxima ante caída del servidor: deltas del último intervalo de flush.

## Esquema
```
accounts(uuid BINARY(16) PK, name VARCHAR(16) INDEX, balance BIGINT CHECK(balance>=0), accepts_payments BOOL, updated_at)
transactions(id BIGINT AI PK, from_uuid BINARY(16) NULL, to_uuid BINARY(16) NULL, amount BIGINT,
             type ENUM('PAY','ADMIN','VAULT'), server VARCHAR(32), created_at,
             INDEX(from_uuid,id), INDEX(to_uuid,id))
schema_version(version INT)
```

## Componentes (`net.zyonmc.zyonpay`)
| paquete | responsabilidad |
|---|---|
| `config` | `Settings` (config.yml tipado y validado), `Messages` (messages.yml, MiniMessage) |
| `money` | `Money` (parse `1.5k/2M/3B/4T`, formato), `CurrencyFormatter` |
| `storage` | `Database` (Hikari + migración), `AccountRepository`, `TransactionRepository` |
| `economy` | `Account`, `AccountCache`, `EconomyService`, `Flusher` |
| `sync` | `RedisBus` (pub/sub, reconexión; no-op si Redis desactivado) |
| `top` | `TopService` (líder por lock → ZSET → snapshot local; fallback BD) |
| `vault` | `VaultEconomy` |
| `placeholder` | expansión PlaceholderAPI `zyonpay` (solo caché/snapshot, sin I/O) |
| `command` | balance, pay (+history), paytoggle, eco (+tx), zyonpay (reload) |
| `menu` | motor de menús estilo DeluxeMenus leído de `menus.yml` |

## Comandos y permisos
`/balance [jugador]` `zyonpay.balance` / `.others` · `/pay <jugador> <monto>` `zyonpay.pay` ·
`/paytoggle` `zyonpay.paytoggle` · `/baltop` `zyonpay.baltop` · `/payhistory [jugador]` (= `/pay history`)
`zyonpay.history` / `.others` · `/eco give|take|set|reset` `zyonpay.eco` · `/eco tx <jugador>` `zyonpay.tx` ·
`/zyonpay reload` `zyonpay.reload` · `zyonpay.admin` agrupa eco, tx, reload y los `.others`.

`/pay`: monto con sufijos, mínimo configurable, sin auto-pago, respeta `accepts_payments`, soporta destinatario offline
(`pay.allow-offline`). Retira del emisor en caché, ingresa en destino; si falla el ingreso se reembolsa.

## Menús (`menus.yml`)
Subconjunto de DeluxeMenus: `menu_title`, `size`, `open_command`, `items`, `material`, `slot`/`slots` (rangos `10-16`),
`display_name`, `lore`, `amount`, `glow`, `view_requirement` (`has permission`/`!has permission`),
`left_click_commands`/`right_click_commands`/`click_commands`. Acciones: `[close]`, `[player]`, `[console]`, `[message]`,
`[openmenu]`, `[nextpage]`, `[prevpage]`. Extensión propia: `data_source: baltop|history` y un ítem con `slots` y
placeholders `{rank} {name} {balance} {balance_short}` (baltop) / `{type} {other} {amount} {direction} {server} {date}`
(history) se repite por cada fila. Un solo motor renderiza cualquier menú; los datos se cargan async antes de abrir.

## Placeholders PAPI
`%zyonpay_balance%` `_balance_raw` `_balance_short` `_accepts_payments` `_rank` `_currency_symbol`
`_currency_singular` `_currency_plural` `_top_<n>_name` `_top_<n>_balance` `_top_<n>_balance_short` `_top_<n>_balance_raw`.

## Pruebas
JUnit 5: parser/formato de montos, `Account` (retiro, tope, concurrencia), parser de slots.
Repositorios SQL: manual contra MariaDB real (sin Testcontainers en v1).

## Fuera de alcance v1
Varias monedas, bancos, intereses, Folia, requirements avanzadas de DeluxeMenus.
