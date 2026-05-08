# Agenew Product Line Categories

Use these categories for customer-facing product introductions and product-line overviews. Do not group by H-series/M-series unless the customer explicitly asks for series naming.

For broad questions such as "公司现在有哪些产品", "介绍公司产品线", or "有哪些模块", answer by these four categories. Never answer as "H 系列" and "M 系列"; that grouping is obsolete for customer introductions.

## 4G SoM

LTE smart modules for Android terminals that need cellular connectivity, Wi-Fi/BT, GNSS, and common multimedia/peripheral interfaces.

- H1502BQ, H1502RQ, H1502UQ: LTE Cat4, Wi-Fi/BT, Eurasia/South America variants.
- H1503BQ, H1503RQ, H1503UQ: LTE Cat4, Wi-Fi/BT, Eurasia/America variants.
- H1504TQ: LTE Cat4, Wi-Fi/BT, China/Eurasia/Latin America variants.
- H1641BP, H1641UP: LTE Cat4, Wi-Fi/BT, China/India variants.
- H1641RP, H164YP, M1642ZP: LTE Cat7 class modules for higher 4G data requirements and regional variants.

## 5G SoM

5G NR smart modules for terminals that need NSA/SA 5G connectivity, higher bandwidth, Wi-Fi 6 class wireless, GNSS, and Android-based edge applications.

- M293GO: Smart 5G module, Android 13, MediaTek MT8791T platform, 5G NR and Wi-Fi 6/BT5.2.
- M318GO: Smart 5G module, Android 13, MediaTek MT8791 platform, 5G NR and Wi-Fi 6/BT5.2.

## AI SoM

High-performance smart-computing modules for multimedia, edge AI, robotics, conferencing, AR/VR, live streaming, and compute-heavy Android terminals.

- M3281V: MT8893 platform, Android 15, 48 TOPS AI performance, 16GB LPDDR5X + 128GB UFS.
- M328L: MT8371 platform, Android 15, 10 TOPS AI performance, Wi-Fi 6E/BT5.3, 8GB LPDDR5X + 128GB UFS.
- M328S: MT8391 platform, Android 15, 10 TOPS AI performance, 8GB LPDDR5X + 128GB UFS.

## WIFI SoM

Wi-Fi smart modules for products that do not need cellular connectivity but require Android, Wi-Fi/BT, display/camera/audio, and rich peripheral interfaces.

- M274F: Android 13, Wi-Fi 6/BT5.2, 8GB LPDDR4X + 64GB eMMC.
- M274K: Android 13, Wi-Fi 6/BT5.2, LPDDR4X or DDR4 memory options with 64GB eMMC.

## Answering Guidance

- Start broad product-line introductions with the four categories above in order: 4G SoM, 5G SoM, AI SoM, WIFI SoM.
- For model-specific questions, normalize spoken model names first, then look up the model in `product-docs/model-aliases.md`, `product-docs/product-briefs.md`, and the extracted specification text.
- For exact parameters, verify against `product-docs/extracted/*.txt` and cite the source model/version/filename.
- If a customer asks for model selection, ask about network requirement, operating system, display/camera/audio interfaces, size, power, target region, and AI performance needs.
