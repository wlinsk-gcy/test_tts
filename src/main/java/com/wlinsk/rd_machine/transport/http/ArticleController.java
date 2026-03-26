package com.wlinsk.rd_machine.transport.http;

import com.wlinsk.rd_machine.article.ArticleCatalogService;
import com.wlinsk.rd_machine.article.ArticleSummary;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
@RequestMapping("/api/articles")
public class ArticleController {

    private final ArticleCatalogService articleCatalogService;

    public ArticleController(ArticleCatalogService articleCatalogService) {
        this.articleCatalogService = articleCatalogService;
    }

    @GetMapping
    public List<ArticleSummary> listArticles() {
        return articleCatalogService.listArticles();
    }
}