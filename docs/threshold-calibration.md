# 相关性门槛校准

问答前的相关性门槛：候选的向量相似度 ≥ `ZHIKU_RETRIEVAL_MIN_SCORE`（默认 0.45），或问题二元组覆盖率 ≥ `ZHIKU_RETRIEVAL_MIN_LEXICAL`（默认 0.35），才会交给模型；一个都不剩就直接回答「资料不足」。

**0.45 是未校准的起始值。** 余弦相似度的分布随 embedding 模型变化很大，换模型就要重新校准。仓库里没有用真实模型跑出的校准结论；下面的脚本是留给接入真实模型时用的。

## 怎么跑

1. 用要上线的 embedding 模型启动服务端（`dev` 配置），并**临时关掉门槛**，让低分候选也出现在检索结果里：

   ```bash
   cd server && SPRING_PROFILES_ACTIVE=dev \
     ZHIKU_EMBEDDING_PROVIDER=ollama ZHIKU_EMBEDDING_MODEL=bge-m3 \
     ZHIKU_RETRIEVAL_MIN_SCORE=-1 ZHIKU_RETRIEVAL_MIN_LEXICAL=2 \
     mvn spring-boot:run
   ```

2. 运行脚本（只调用检索调试接口，不调用对话模型）：

   ```bash
   # 新建一个知识库，写入 scripts/example-eval 的虚构文档，用内置的 12 个问题
   node scripts/calibrate-threshold.mjs --out calibration.json
   # 更有意义的做法：用真实知识库和真实问题
   node scripts/calibrate-threshold.mjs --kb <知识库 id> --cases my-cases.json --k 10
   ```

   `my-cases.json`：`[{"question": "…", "expect": "应命中的文档标题"}, {"question": "…", "expect": null}]`，`expect: null` 表示这个问题应拒答。应答和应拒答的问题都要有，各几十个以上才有参考价值。

3. 读输出：每个问题的「应命中文档的最高分」和「全部候选的最高分」，以及 0.10–0.90 每隔 0.05 的召回（应答问题中应命中文档过门槛的比例）和误放行（应拒答问题中有候选过门槛的比例）。脚本给出「召回 − 误放行」最高的阈值，仅供参考。

4. 选值时注意：
   - 词覆盖门槛是「或」关系，会把一部分低向量分的应答问题救回来；脚本只看向量分，结论偏保守。
   - 误放行的代价是模型拿到不相关资料（回答后还有引用核对），漏召回的代价是答「资料不足」。企业制度问答通常宁可多拒答。
   - 选好后改 `.env` 的 `ZHIKU_RETRIEVAL_MIN_SCORE`，恢复 `ZHIKU_RETRIEVAL_MIN_LEXICAL`，再跑一次 `scripts/eval-baseline.mjs` 看拒答与命中。

内置 mock embedding（字符二元组哈希）的分数只用来验证脚本能跑通，不能拿来定生产阈值。
