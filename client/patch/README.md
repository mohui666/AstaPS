# hk4e-patch-universal

Genshin Impact encryption patch that should work on 6.5 and forward for a while

## Optional card/pass price diagnostics

Before launching the game, set `LUNAGC_SDK_DIAGNOSTICS=1` or create
`%TEMP%\lunagc-sdk-diagnostics.txt`. The marker also works when the game is
launched through an elevated launcher. Remove it and restart to disable diagnostics.

Card/pass diagnostic entries in `%TEMP%\lunagc-patch.log` contain only known public product IDs
and whether currency and display-price fields are present.
Account fields and arbitrary product or display strings are excluded.
Unsupported client signatures are reported and skipped. These diagnostics do not fix missing products.

## Local checkout

The AstaPS launchers save `serverUrl` and `localRecharge: true` in
`astaps-client.json` beside the game executable. `ASTAPS_SERVER_URL` can override
the address for a launch; `LUNAGC_LOCAL_RECHARGE=1` also enables local checkout.
The patch recognizes the server's supported product IDs at the product-ready
function entry. After AstaPS persists delivery and acknowledges the recharge,
the subsequent `login_pay` call completes locally instead of opening an SDK
cashier or identity-entry page. Login and account verification calls retain
their original implementation.

## Optional IFix stream capture

Set `LUNAGC_IFIX_DUMP_DIR` to an absolute output directory before launching the game,
or write that path as UTF-8 to `%TEMP%\lunagc-ifix-dump-dir.txt` when using a launcher
that does not inherit the environment. Remove the variable/marker and restart to
disable capture; `LUNAGC_DISABLE=ifix` also disables it.

This opt-in diagnostic currently supports one verified `YuanShen.exe` and metadata
pair. Both SHA-256 hashes and the loader signature must match before the hook is
installed. Unsupported clients are skipped. The callback copies a recognized
MemoryStream without changing its position, data or the loader's arguments.

Valid compiled streams are stored as `ifix-<sha256>.bin`, with a 128 MiB per-stream
limit, a 256 MiB session limit and at most 16 accepted observations. Existing files
are reused only when their length and digest match. The patch log reports capture
status, sizes and paths; the diagnostic does not fix or replace game logic. A
successful hook installation does not mean that a patch stream has been observed.

## Credits
[xeondev](https://git.xeondev.com/reversedrooms/hk4e-patch) for the original patch and [oureveryday](https://github.com/oureveryday/) for the original hk4e-patch-universal
