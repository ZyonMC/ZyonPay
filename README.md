# ZyonPay

Multi-server, multi-currency economy for Paper, backed by **MariaDB** (source of truth) and **Redis** (cross-server
sync). Integrates with **Vault**, shows balance rankings and payment history in configurable menus, and exposes
PlaceholderAPI placeholders.

## Features

- Several currencies, each with its own permissions (`zyonpay.balance.<currency>`, `zyonpay.pay.<currency>`...)
- Pay online or offline players, or everyone online with `*`; block payments with `/paytoggle`
- Balance ranking (baltop) and payment history menus (`menus.yml`)
- Transaction log with lookup and revert
- Admin data tools: backup, restore, purge, migrate, switch-currency
- Vault economy provider; PlaceholderAPI (optional)

## Requirements

- Paper 26.3, Java 25
- MariaDB and Redis
- Vault (required), PlaceholderAPI (optional)
- HikariCP, MariaDB driver and Lettuce are downloaded by Paper at startup (not shaded)

## Commands

| Command | Permission | Description |
|---|---|---|
| `/balance [player] [currency]` (`/bal`, `/money`) | `zyonpay.balance` | Show a balance |
| `/pay <player\|*> <amount\|percent> [currency]` | `zyonpay.pay` | Send money |
| `/pay history [player]` | `zyonpay.history` | Payment history |
| `/paytoggle [player\|*]` | `zyonpay.paytoggle` | Block or allow payments |
| `/eco <give\|take\|set\|reset\|setmax\|tx> ...` | `zyonpay.eco` | Economy administration |
| `/zyonpay <reload\|tx\|history\|backup\|restore\|purge\|migrate\|switch-currency>` | `zyonpay.admin` | Plugin admin tools |

`zyonpay.*` grants everything; the full permission list is documented at the top of `config.yml`.

## Configuration

`config.yml` (restart required, except the `currencies` section, which `/zyonpay reload` applies), plus `messages.yml`
and `menus.yml`. Set the MariaDB and Redis credentials in `config.yml`; the default password is a placeholder
(`change-me`).

## Build

```bash
./gradlew build
./gradlew test    # includes integration tests against real MariaDB and Redis
```

Package: `net.zyonmc.zyonpay`
