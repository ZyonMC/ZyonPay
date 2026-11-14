# ZyonPay - Roadmap

Features of [RedisEconomy](https://emibergo.gitbook.io/rediseconomy) adopted by ZyonPay, in order of effort.
ZyonPay keeps its own base: MariaDB is the source of truth, Redis only synchronizes servers, menus are configurable.

## Phase 1 - Payments

- [x] `pay-tax` per currency, with `tax-only-pay` to also tax money deposited through Vault.
- [x] `pay.allow-percentage` - `/pay <player> 10%` sends 10% of the payer's balance.
- [x] `pay.cooldown-ms` - minimum wait between payments.
- [x] `/pay * <amount>` - pay everyone online on this server (`zyonpay.pay.all`).
- [x] `/paytoggle <player|*>` - block specific payers; with no arguments it lists them; `*` refuses all payments.
- [x] `tab.complete-chars` / `tab.online-only` - minimum letters before suggesting, and local-only suggestions.

## Phase 2 - Admin and traceability

- [x] Reason in `/eco give|take|set|reset`, stored in the history.
- [x] `register-calls-verbosity` (0-3) + `call-blacklist-regex` - store the plugin that made a Vault operation.
- [x] `/zyonpay tx <player> <id>` shows a transaction; `... revert` reverses it with a new transaction.
- [x] `/zyonpay history <player> [before] [after]` - browse history by date (`yyyy-MM-dd_HH.mm.ss`).
- [x] `history-ttl-days` per currency - periodic `DELETE` of old transactions (no Redis 7.4 needed).
- [x] Per-player balance cap: `/eco setmax <player> <amount|reset> [currency]`.
- [x] `/zyonpay backup <file>` and `/zyonpay restore <file>`.
- [x] `/zyonpay purge <regex>` - delete accounts (asks for `confirm`).
- [x] Migration from another Vault economy (`migration.enabled` + `/zyonpay migrate`).
- [ ] Web message editor (low priority; needs an external service).

## Phase 3 - Placeholders

All of them accept an optional `_<currency>` suffix.

- [x] `%zyonpay_bal_short%` - abbreviated balance with configurable units (`placeholders.short-units`).
- [x] `%zyonpay_bal_decformat<pattern>%` - custom `DecimalFormat` (`bal_<currency>_decformat<pattern>` for another currency).
- [x] `%zyonpay_totsupply%` (+ `_formatted`, `_short`) - total money in circulation.
- [x] `%zyonpay_top_<n>_playerprefix%` / `_playersuffix%` - Vault chat prefix/suffix of position N.
- [x] `%zyonpay_top_position%` - the player's position in the baltop.
- [x] `%zyonpay_maxbal%` - the player's balance cap (their own, or the currency's).
- [x] `placeholders.top-accounts` - how many positions the top placeholders cover.
- [x] `placeholders.cache-interval-ms` - cache of the Vault prefix/suffix lookups.

## Phase 4 - Multi-currency

- [x] DB: `balances(uuid, currency)`; `transactions.currency`; migration assigns existing accounts to the default currency.
- [x] Config: `currencies:` and `default-currency`.
- [x] Commands: optional `[currency]` in `/balance`, `/pay`, `/baltop`, `/eco`; permissions `zyonpay.<command>.<currency>` for non-default currencies.
- [x] Vault: one `Economy` per currency; only the default is registered as a service (`ZyonPayPlugin#vault(id)` exposes the rest).
- [x] Redis: `REFRESH` and `PAID` carry the currency; one baltop per currency.
- [x] Placeholders and menus: `_<currency>` suffix; menus accept `currency:`.
- [x] `/zyonpay switch-currency <a> <b>` - swap balances between two currencies (restart all servers afterwards).
- [x] Live currency reload with `/zyonpay reload` (`default-currency` still needs a restart).

## Decided against / to decide

- Redis-only storage (RedisEconomy): rejected; without well-configured persistence balances can be lost.
- `cluster-id`: optional; several networks on the same DB/Redis already work with `redis.prefix`.
- Vault bank API: only if some plugin needs it.
- Per-account/currency threads (`executor-threads`): ZyonPay uses a cache with periodic flush; not needed unless contention is measured.
