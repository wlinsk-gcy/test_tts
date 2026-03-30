package com.wlinsk.rd_machine.transport.http;

import com.wlinsk.rd_machine.article.ArticleCatalogService;
import com.wlinsk.rd_machine.article.ArticleSummary;
import com.wlinsk.rd_machine.model.Result;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/articles")
public class ArticleController {

    private final ArticleCatalogService articleCatalogService;

    public ArticleController(ArticleCatalogService articleCatalogService) {
        this.articleCatalogService = articleCatalogService;
    }

    @GetMapping
    public Result<List<ArticleSummary>> listArticles() {
        List<ArticleSummary> articleSummaries = articleCatalogService.listArticles();
        return Result.ok(articleSummaries);
    }
}