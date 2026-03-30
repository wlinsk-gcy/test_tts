package com.wlinsk.rd_machine.article;

public record ArticleDetail(
        String title,
        String author,
        String language,
        String content
) {
}