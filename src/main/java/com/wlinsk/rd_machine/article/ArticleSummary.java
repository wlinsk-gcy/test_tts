package com.wlinsk.rd_machine.article;

public record ArticleSummary(
        String articleId,
        String title,
        String author,
        String language
) {
}
