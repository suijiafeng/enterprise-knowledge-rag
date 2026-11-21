-- 支持 PDF 与 Word .docx：库里保存原文件，建索引时提取文本（见 DocumentText）。
ALTER TABLE content_blob DROP CONSTRAINT IF EXISTS content_blob_media_type_check;
ALTER TABLE content_blob ADD CONSTRAINT content_blob_media_type_check CHECK (media_type IN (
  'text/markdown', 'text/plain', 'application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'));
