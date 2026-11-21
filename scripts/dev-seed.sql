-- LOCAL development seed with neutral fictional identifiers. Never run against production.
-- Matches the example commands in README.md.
INSERT INTO knowledge_base (id, enterprise_id, name)
VALUES ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000e1', '员工公共制度库（示例）')
ON CONFLICT DO NOTHING;
INSERT INTO kb_grant (kb_id, user_id)
VALUES ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000a1'),
       ('00000000-0000-4000-8000-0000000000b1', '00000000-0000-4000-8000-0000000000a2')
ON CONFLICT DO NOTHING;
-- 虚构的用户目录镜像（生产由身份提供方同步）。a3 不在任何知识库里，用来演示授权。
INSERT INTO directory_user (id, enterprise_id, display_name, email, department)
VALUES ('00000000-0000-4000-8000-0000000000a1', '00000000-0000-4000-8000-0000000000e1', '林知远（示例）', 'lin.zhiyuan@example.com', '信息技术部'),
       ('00000000-0000-4000-8000-0000000000a2', '00000000-0000-4000-8000-0000000000e1', '周若溪（示例）', 'zhou.ruoxi@example.com', '人力资源部'),
       ('00000000-0000-4000-8000-0000000000a3', '00000000-0000-4000-8000-0000000000e1', '陈一鸣（示例）', 'chen.yiming@example.com', '财务部'),
       ('00000000-0000-4000-8000-0000000000a4', '00000000-0000-4000-8000-0000000000e1', '王可欣（示例）', 'wang.kexin@example.com', '行政部')
ON CONFLICT DO NOTHING;
