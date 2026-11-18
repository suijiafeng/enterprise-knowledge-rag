-- How many vector candidates were fed to the optional BM25+RRF rerank. Null when rerank was off.
ALTER TABLE eval_run ADD COLUMN rerank_pool integer CHECK (rerank_pool IS NULL OR rerank_pool >= 1);
