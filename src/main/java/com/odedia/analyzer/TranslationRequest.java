package com.odedia.analyzer;

public record TranslationRequest(
    String sourceFile,
    String sourceLanguage,
    String targetFile,
    String targetLanguage) {
}
