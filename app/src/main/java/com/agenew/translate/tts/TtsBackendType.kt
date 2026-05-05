package com.agenew.translate.tts

enum class TtsBackendType(
    val displayName: String,
    val assetFolderCandidates: List<String>,
    val folderHint: String,
) {
    BERT_VITS2(
        displayName = "BertVITS2",
        assetFolderCandidates = listOf("bert-vits2-MNN"),
        folderHint = "bert-vits2-MNN",
    ),
    SUPERTONIC(
        displayName = "Supertonic",
        assetFolderCandidates = listOf("supertonic-tts-mnn", "supertonic"),
        folderHint = "supertonic-tts-mnn",
    );
}
