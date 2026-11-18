-- The receipt row is inserted when the request id is accepted. completed_at is set in the same
-- transaction that stores the message, so a saved answer and its receipt commit together.
-- digest remains the SHA-256 hex of knowledge base id, conversation id and question.
ALTER TABLE chat_request ADD COLUMN completed_at timestamptz;

COMMENT ON COLUMN chat_request.digest IS
  'SHA-256 hex of kb id, conversation id and question. The same client_request_id with a different digest is a conflict.';
