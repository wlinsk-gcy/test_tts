import type { DebugArticle } from "../types";

type Props = {
  articles: DebugArticle[];
  activeArticleId?: string;
  onSelect: (article: DebugArticle) => void;
};

export function ArticleList({ articles, activeArticleId, onSelect }: Props) {
  return (
    <section className="panel">
      <div className="panel-header">
        <h2>Articles</h2>
        <span>{`${articles.length} local`}</span>
      </div>
      <div className="article-grid">
        {articles.map((article) => (
          <button
            key={article.id}
            className={activeArticleId === article.id ? "article-button active" : "article-button"}
            onClick={() => onSelect(article)}
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
