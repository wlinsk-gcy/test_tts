package com.wlinsk.rd_machine.basic.model.bo;

public record ArticleDetail(
        String title,
        String author,
        String language,
        String content
) {
}