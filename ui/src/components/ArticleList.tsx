import type { ArticleSummary } from "../types";

type Props = {
  articles: ArticleSummary[];
  loading: boolean;
  activeArticleId?: string;
  onSelect: (articleId: string) => void;
};

export function ArticleList({ articles, loading, activeArticleId, onSelect }: Props) {
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>Articles</h2>
        <span>{loading ? "Loading..." : `${articles.length} loaded`}</span>
      </div>
      <div className="article-grid">
        {articles.map((article) => (
          <button
            key={article.articleId}
            className={activeArticleId === article.articleId ? "article-button active" : "article-button"}
            onClick={() => onSelect(article.articleId)}
            type="button"
          >
            <strong>{article.title}</strong>
            <span>{article.author}</span>
            <small>{article.language}</small>
          </button>
        ))}
      </div>
    </section>
  );
}