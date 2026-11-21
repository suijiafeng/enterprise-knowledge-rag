# 架构边界 · PRD v0.3

## 当前存在
- server：单个 Spring Boot 应用，包 auth / knowledgebase / document / ingestion / embedding / retrieval / chat / evaluation / config / overview / audit / platform。
  - 身份：JWT 资源服务器；授权库每请求从数据库读取；挂件令牌限单库且只能调用 /api/chat。
  - 数据：Flyway V1–V24（知识库与授权、文档与内容版本、内容与摄取任务、切块、向量、会话与来源依赖、审计、反馈、评测、问答结果状态、文档删除、反馈处理记录、问答请求、前序对话依赖、清理领取令牌、评测用例来源反馈、检索轨迹、评测样本字段、知识库配置版本、反馈处理修订号、评测运行的参数与生成结果）。
  - 配置：每库一份候选，验证（真实检索 + 检索评测运行，记录为依据）后发布为新版本，旧版本可回滚为新版本；修订号乐观锁。新请求开始时读取一次生效配置，轨迹与 start 事件记录配置版本。Embedding 与切块不在其中。
  - 删除：标记删除即退出资格、检索、引用、评测证据与总览统计，依赖它的历史回答隐藏；数据库租约任务随后清理原文、切块、向量与摄取任务，状态 pending/running/done/failed 可查，失败只写 ERROR 日志，无外部告警；备份不在清理范围内。
  - 摄取：数据库租约队列（FOR UPDATE SKIP LOCKED），租约内一次性写入切块与向量；有界并发槽（zhiku.ingestion.concurrency）处理，一个卡住的任务只占一个槽；未配置 embedding 的实例不认领任务。无 @Async。
  - 检索：先按授权与效力得出合格版本，再在同一 embedding 模型与维度内做精确余弦检索；无 ANN、无融合、无重排。问答、调试与评测走同一链路，每次检索按 traceId 保存各阶段与候选的元数据（不存问题和正文），问答追加生成阶段。轨迹按 zhiku.retention.trace-days（默认 90 天）、请求去重记录按 request-days（默认 7 天）分批清理，正式保留期待业务确认。
  - 问答：SSE draft → validated → done；调用模型前与草稿流式期间按间隔重读授权与效力，披露前检查引用编号与前序对话依赖；只保存完成的回答；客户端请求编号只受理一次。模型适配器的取消、空闲与总时限作用于 HTTP 交换本身。
- admin-web：管理端，未登录为示例模式，登录后全部页面使用真实接口；令牌只在页面内存。
- widget：可嵌入问答组件，使用挂件短期令牌，不存长期凭据。
评测运行始终计算检索 Hit@K、片段 Recall@K 和 MRR；生成和忠实度/相关性评审是可选项。没有对话模型时不编造答案或分数。模拟评审只做必要事实的词面包含，供测试和离线对照。重排是候选池内的字符二元组 BM25 与向量分加权，默认关闭；没有单独的词法召回。切块窗口来自部署配置，评测运行不整库重建。已发布版本的切块可以按块替换并重写向量。回答正确性的人工评测仍未实现。反馈关闭的回归验证只检查检索召回，不检查回答。
未实现：生产 IdP、对话摘要、独立词法召回与交叉编码器、引用语义验证、回答正确性人工评测、切块参数变更后的整库重建。

## 目标模块依赖（设计约束，非实现清单）
单体按业务包：auth → 身份/权限；knowledgebase → 授权库；document → 内容版本/业务效力；ingestion → 持久任务与解析；retrieval → 合格版本内检索；chat → 编排；citation → 来源解析；evaluation → 独立真值与回归；audit → 脱敏追加事件。
箭头为依赖方向：controller → application → domain；infrastructure 实现 application 的 port。domain 不依赖 Spring/HTTP/数据库/模型 SDK。模块之间只调公开应用接口/事件，不跨模块读 Repository。shared 只放极小通用值类型，禁止万能 util 或基础业务 Service。
chat 依赖 auth、document 的资格选择、retrieval 和 citation 的公开接口；retrieval 不反向依赖 chat。身份信息由服务端建立。审核通过但未生效、当前生效、索引未就绪是独立事实。

## 数据/检索关键边界
未来 migration 应区分 document、content_version、business_effectivity、index_version、ingestion_job、source_dependency。ingestion_job 持久落库/租约/幂等完成后才可声称可恢复任务；@Async 不是队列。
先确认主题、受众、业务时区和替代关系，再在授权有效版本集合内检索。示例策略仅覆盖一个已确认主题/适用范围；不实现替代关系图或完整业务引擎。不得直接用于生产判定。
embedding 维度不写死：每行记录模型与维度，检索只比较同一空间。没有模型不生成假向量（测试用确定性测试向量，仅存在于测试代码）。中文词法、ANN、reranker 以后用评测证明收益。

## 问答检索链路

1. 查询改写：有对话历史时把追问改写成独立问题（`zhiku.chat.query-rewrite`：llm，失败回退启发式；heuristic；off）。改写只用于检索，事实仍只来自本轮检索到的合格来源。
2. 双路召回：合格版本内的向量检索（合格 chunk 数 ≥ `ann-min-chunks` 时走 HNSW，`hnsw.iterative_scan = relaxed_order`，否则精确余弦）＋ 字符二元组 `tsvector` 词法召回（GIN 索引，中文不依赖分词器）。
3. RRF 融合（k = 60）。
4. 相关性门槛：向量相似度 ≥ `min-score` 或问题二元组覆盖率 ≥ `min-lexical-coverage` 才保留。一条都不剩时直接返回 NO_EVIDENCE，不调用模型。阈值随 embedding 模型变化，需要用评测集校准。
5. 重排：候选池内字符二元组 BM25 与向量分加权，关键词堆砌（二元组重复率高）的候选降权。不是交叉编码器。
6. 生成：来源正文里的指令式句子（忽略规则、改变角色、不要引用……）替换为屏蔽标记后再给模型。回答后核对引用编号、版本和授权，再按字面检查每句是否能在所引来源中找到（`support`），只提示不拦截。模型自己说"资料不足"且无引用时，按 NO_EVIDENCE 正常回答展示。

chunk 按 Markdown 章节切分，不跨章节；每个 chunk 存「文档标题 › 章节路径」上下文，向量化与词法召回都带上它。
