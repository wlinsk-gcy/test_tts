package com.wlinsk.rd_machine.article;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ArticleCatalogService {

    private final Map<String, ArticleDetail> articles = new LinkedHashMap<>();

    public ArticleCatalogService() {
        register(new ArticleDetail(
                "shaonian-runtu",
                "少年閏土",
                "魯迅",
                "zh-HK",
                "我於是日日盼望新年，新年到，閏土也就到了。好容易到了年末，有一日，母親告訴我，閏土來了，我便飛跑地去看。"
                        + "他正在廚房裡，紫色的圓臉，頭戴一頂小氈帽，頸上套一個明晃晃的銀項圈。"
                        + "他見人很怕羞，只是不怕我，沒有旁人的時候，便和我說話，於是不到半日，我們便熟識了。"
        ));
        register(new ArticleDetail(
                "the-gift-of-the-magi",
                "The Gift of the Magi",
                "O. Henry",
                "en",
                "One dollar and eighty-seven cents. That was all. And sixty cents of it was in pennies."
                        + " Della counted it three times. One dollar and eighty-seven cents, and the next day would be Christmas."
                        + " There was clearly nothing to do but flop down on the shabby little couch and howl."
        ));
        register(new ArticleDetail(
                "be-water",
                "像水一樣",
                "佚名",
                "zh-HK",
                "水看似柔弱，卻能沿著地勢前進，也能在漫長歲月裡改變石頭的形狀。"
                        + "它不急於爭先，卻總能找到出口，因此常被用來比喻靈活與堅韌。"
        ));
    }

    public List<ArticleSummary> listArticles() {
        return articles.values().stream().map(ArticleDetail::toSummary).toList();
    }

    public ArticleDetail getRequiredArticle(String articleId) {
        ArticleDetail article = articles.get(articleId);
        if (article == null) {
            throw new IllegalArgumentException("Unknown articleId: " + articleId);
        }
        return article;
    }

    private void register(ArticleDetail articleDetail) {
        articles.put(articleDetail.articleId(), articleDetail);
    }
}
