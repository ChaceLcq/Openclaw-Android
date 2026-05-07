# Agenew Product Model Aliases

Use this file before deciding that a customer-mentioned model is not in the product documents.
ASR may transcribe digits as Chinese numerals. Normalize spoken model names to canonical model codes.

## Normalization Rules

- Convert Chinese numerals in model-like strings to digits: 零=0, 一=1, 二=2, 三=3, 四=4, 五=5, 六=6, 七=7, 八=8, 九=9.
- Remove spaces and punctuation inside model codes.
- Preserve Latin letters such as H, M, BQ, RQ, UQ, TQ, BP, RP, UP, YP, ZP, GO, L, S, F, K, V.
- Example: `M三二八一V`, `M 三 二 八 一 V`, and `M3281V` all mean `M3281V`.
- Example: `H一五零二BQ` means `H1502BQ`.

## Aliases

- `H1502BQ`: `H一五零二BQ`, `H 一 五 零 二 B Q`
- `H1502RQ`: `H一五零二RQ`, `H 一 五 零 二 R Q`
- `H1502UQ`: `H一五零二UQ`, `H 一 五 零 二 U Q`
- `H1503BQ`: `H一五零三BQ`, `H 一 五 零 三 B Q`
- `H1503RQ`: `H一五零三RQ`, `H 一 五 零 三 R Q`
- `H1503UQ`: `H一五零三UQ`, `H 一 五 零 三 U Q`
- `H1504TQ`: `H一五零四TQ`, `H 一 五 零 四 T Q`
- `H1641BP`: `H一六四一BP`, `H 一 六 四 一 B P`
- `H1641RP`: `H一六四一RP`, `H 一 六 四 一 R P`
- `H1641UP`: `H一六四一UP`, `H 一 六 四 一 U P`
- `H164YP`: `H一六四YP`, `H 一 六 四 Y P`
- `M1642ZP`: `M一六四二ZP`, `M 一 六 四 二 Z P`
- `M274F`: `M二七四F`, `M 二 七 四 F`
- `M274K`: `M二七四K`, `M 二 七 四 K`
- `M293GO`: `M二九三GO`, `M 二 九 三 G O`
- `M318GO`: `M三一八GO`, `M 三 一 八 G O`
- `M3281V`: `M三二八一V`, `M 三 二 八 一 V`
- `M328L`: `M三二八L`, `M 三 二 八 L`
- `M328S`: `M三二八S`, `M 三 二 八 S`
