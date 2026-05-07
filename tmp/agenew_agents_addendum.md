<!-- AGENEW_PRODUCT_KB_START -->
## Agenew product knowledge base

When the user asks about Agenewtech products, smart modules, specifications, model selection, or customer-facing product introductions, answer from the local product knowledge base. Do not ask the user for a product category when the message contains a known model code or spoken model code.

Important ASR normalization: customer speech may transcribe product-code digits as Chinese numerals. Convert Chinese numerals in model-like strings to digits before searching. For example, `M三二八一V` means `M3281V`, `M三二八S` means `M328S`, and `H一五零二BQ` means `H1502BQ`.

Before saying a product model is not found, check or use:
- `product-docs/model-aliases.md`
- `product-docs/product-briefs.md`
- `product-docs/product-catalog.md`
- `product-docs/extracted/`

M3281V quick facts:
- Canonical model: M3281V. Spoken/ASR forms include M三二八一V and M 三 二 八 一 V.
- Product positioning: smart computing power module.
- Processor/platform: MediaTek MT8893 octa-core 4nm processor, with ARM Immortalis-G720 GPU and 7th generation APU 790.
- Operating system: Android 15.
- AI performance: 48 TOPS.
- Memory: 128GB UFS + 16GB LPDDR5X.
- Display/video: main screen up to WQHD (1600x3680)@180fps or 4K (2160x3840)@144fps; supports 8K@30fps video encoding and 8K@60fps video decoding.
- Interfaces: audio/video I/O and rich GPIO, including MIPI DSI/CSI, touch, camera, mic/speaker, UART, USB, I2C, SPI, PCIe, SD card, ADC, PWM, I2S, GPIO.
- Package/size: LGA SMT module, 544 pins, about 42mm x 46mm x 2.8mm in V2.3 English specification.
- Typical applications: video conferencing, live streaming, gaming, edge computing, robotics, drones, AR/VR and other terminal products.
- Source: Agenewtech_M3281V_Smart Module Specification_V2.3.pdf / product-docs/extracted/m3281v-en-v2-3.txt.

Known product models in this workspace:
- H1502BQ / spoken H一五零二BQ: Smart Module _V1.3 (English V1.3, `h1502bq-en-v1-3.txt`). OS Android 12/Android 13/Android 14; memory 2GB+8GB/2GB+32GB/3GB+32GB /4GB+32GB; region Eurasia/South America
- H1502RQ / spoken H一五零二RQ: Smart Module _V1.3 (English V1.3, `h1502rq-en-v1-3.txt`). OS Android 14; memory 32GB eMMC + 3GB LPDDR4X/32GB eMMC + 4GB LPDDR4X/64GB eMMC + 4GB LPDDR4X; region Eurasia/South America
- H1502UQ / spoken H一五零二UQ: Smart Module _V1.3 (English V1.3, `h1502uq-en-v1-3.txt`). OS Android 12/Android 13/Android 14; memory 2GB+8GB/2GB+32GB/3GB+32GB /4GB+32GB; region Eurasia/South America
- H1503BQ / spoken H一五零三BQ: Smart Module _V1.7 (English V1.7, `h1503bq-en-v1-7.txt`). OS Android 13go/Android 13/Android 14go/Android 14; memory 1GB+8GB/1GB+16GB/2GB+32GB/3GB+32GB/4GB+64GB; region Eurasia/America
- H1503RQ / spoken H一五零三RQ: Smart Module _V1.3 (English V1.3, `h1503rq-en-v1-3.txt`). OS Android 14; memory 4GB+64GB/6GB+64GB/8GB+64GB; region Eurasia/America
- H1503UQ / spoken H一五零三UQ: Smart Module _V1.7 (English V1.7, `h1503uq-en-v1-7.txt`). OS Android 13go/Android 13/Android 14go/Android 14; memory 1GB+8GB/1GB+16GB/2GB+32GB/3GB+32GB/4GB+64GB; region Eurasia/America
- H1504TQ / spoken H一五零四TQ: Smart Module _V1.5 (English V1.5, `h1504tq-en-v1-5.txt`). OS Android 10 / Android 13; memory 8GB eMMC + 1GB LPDDR3 / 16GB eMMC + 2GB LPDDR3; region China/Eurasia/Latin America
- H1641BP / spoken H一六四一BP: Smart Module _V2.1 (English V2.1, `h1641bp-en-v2-1.txt`). OS Android 11; memory 32GB eMMC + 2GB LPDDR4X (Default settings) / 64GB eMMC + 4GB LPDDR4X(Optional); region China/India
- H1641RP / spoken H一六四一RP: Smart Module _V2.2 (English V2.2, `h1641rp-en-v2-2.txt`). memory 4GB + 64GB / 8GB + 128GB / 8GB + 256GB; region North America, Latin America
- H1641UP / spoken H一六四一UP: Smart Module _V2.1 (English V2.1, `h1641up-en-v2-1.txt`). memory 32GB eMMC + 2GB LPDDR4X (Default) / 64GB eMMC + 4GB LPDDR4X (Optional); region China/India
- H164YP / spoken H一六四YP: Smart Module _V1.3 (English V1.3, `h164yp-en-v1-3.txt`). memory 64 GB eMMC + 4 GB LPDDR4X(Default) / 32 GB eMMC + 3 GB LPDDR4X(Optional); region China/Europe/Asia
- M1642ZP / spoken M一六四二ZP: Smart Module _V2.1 (English V2.1, `m1642zp-en-v2-1.txt`). memory UFS 64G+LPDDR4X 32G; region North America, Latin America
- M274F / spoken M二七四F: Smart Module _V1.1 (English V1.1, `m274f-en-v1-1.txt`). OS Android 13; memory eMMC 5.1 64GB + LPDDR4X 8GB
- M274K / spoken M二七四K: Smart Module _V1.1 (English V1.1, `m274k-en-v1-1.txt`). OS Android 13; memory eMMC 5.1 + 64bit LP4X 1866MHz (Default settings) / eMMC 5.1 + 64bit DDR4 1600MHz (Optional)
- M293GO / spoken M二九三GO: Smart Module _V2.1 (English V2.1, `m293go-en-v2-1.txt`). OS Android 13; memory 64GB UFS + 4GB LPDDR4X（Default）; region China/India/Europe/America/Australia
- M318GO / spoken M三一八GO: Smart Module _V1.1 (English V1.1, `m318go-en-v1-1.txt`). OS Android 13; memory 64GB UFS + 4GB LPDDR4X (default); size 42.5 × 56.5 × 2.85; region China
- M3281V / spoken M三二八一V: Smart Module _V2.3 (English V2.3, `m3281v-en-v2-3.txt`). OS Android 15; memory 128GB UFS + 16GB LPDDR5X; size 42 × 46 × 2.8; region China
- M328L / spoken M三二八L: Smart Module _V1.4 (English V1.4, `m328l-en-v1-4.txt`). OS Android 15; memory 128GB UFS 3.1 + 8GB LPDDR5X; size 42 × 46 × 2.6; region China
- M328S / spoken M三二八S: Smart Module _V2.1 (English V2.1, `m328s-en-v2-1.txt`). OS Android 15; memory 128GB UFS 3.1 + 8GB LPDDR5X; size 42 × 46 × 2.6; region China

If exact parameters are needed and file tools are available, read the extracted text. If file tools are not available, use the quick facts above and clearly say when a value is from the quick facts rather than a full datasheet lookup.
If Chinese extracted text is unreadable, use the English extracted specification and answer in Chinese.
<!-- AGENEW_PRODUCT_KB_END -->
