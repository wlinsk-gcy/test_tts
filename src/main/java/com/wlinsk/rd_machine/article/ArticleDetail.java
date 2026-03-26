package com.wlinsk.rd_machine.article;

public record ArticleDetail(
        String articleId,
        String title,
        String author,
        String language,
        String content
) {

    public ArticleSummary toSummary() {
        return new ArticleSummary(articleId, title, author, language);
    }
}
