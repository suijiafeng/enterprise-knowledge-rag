-- Evaluation runs record the parameters that actually applied, plus optional generation.
-- Chunk size is the deployment chunker used when the index was built; a run does not rechunk.
-- rerank_enabled stays false: the retrieval pipeline has no reranker.
-- Scores are null when they were not measured. Retrieval ground truth remains character spans.
ALTER TABLE eval_run ADD COLUMN context_chunks integer;
ALTER TABLE eval_run ADD COLUMN chunk_max_chars integer;
ALTER TABLE eval_run ADD COLUMN chunk_overlap integer;
ALTER TABLE eval_run ADD COLUMN rerank_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE eval_run ADD COLUMN generate boolean NOT NULL DEFAULT false;
ALTER TABLE eval_run ADD COLUMN judge text;
ALTER TABLE eval_run ADD COLUMN chat_model text;

ALTER TABLE eval_result ADD COLUMN first_relevant_rank integer CHECK (first_relevant_rank IS NULL OR first_relevant_rank >= 1);
ALTER TABLE eval_result ADD COLUMN answer text;
ALTER TABLE eval_result ADD COLUMN generation_ms integer;
ALTER TABLE eval_result ADD COLUMN prompt_tokens integer;
ALTER TABLE eval_result ADD COLUMN completion_tokens integer;
ALTER TABLE eval_result ADD COLUMN faithfulness double precision CHECK (faithfulness IS NULL OR (faithfulness >= 0 AND faithfulness <= 1));
ALTER TABLE eval_result ADD COLUMN relevance double precision CHECK (relevance IS NULL OR (relevance >= 0 AND relevance <= 1));
ALTER TABLE eval_result ADD COLUMN failure text;
ALTER TABLE eval_result ADD COLUMN citation text;
ALTER TABLE eval_result ADD COLUMN judge_reason text;
ALTER TABLE eval_result ADD COLUMN retrieved jsonb;
