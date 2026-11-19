# 问答可靠性

这一节按当前代码说明问答在断线、重复提交和来源失效时怎么做。

**请求编号，不重发。** 每次提问由浏览器生成 `clientRequestId`。管理端把它和知识库、问题一起放进 `sessionStorage`（键 `zhiku.chat.pending`），挂件用另一个键 `zhiku.widget.pending`。两处都不写令牌。SSE 的 `draft` 追加到当前回答。流中断、刷新或网络恢复后，先 `GET /api/chat/requests/{clientRequestId}`。查到已完成且来源仍可见的回答就显示这一次，不再 `POST /api/chat`。挂件令牌只能提问、评价和查询自己的请求，不能拉会话列表。

**回执与内容哈希。** `chat_request` 以 `(enterprise_id, user_id, client_request_id)` 为主键。接受请求时在插入回执；`digest` 是知识库 id、会话 id 和问题的 SHA-256 十六进制，不存问题原文。同一编号、同一摘要返回 `409 REQUEST_DUPLICATE`，不再调用模型。同一编号、不同摘要返回 `409 REQUEST_CONFLICT`。`V26__chat_receipt_completed.sql` 增加 `completed_at`，在保存消息的同一事务里写入，所以“回答已落库”和“回执已完成”一起提交。插入发生在生成之前：这样并发的第二次提交会撞上主键，而不是等模型跑完才发现重复。代价是生成失败或进程退出后，这枚编号不能换一题再用，只能查询原来的结果。

**历史游标。** `GET /api/conversations/{id}/messages/page?limit=&before=` 的游标是 `(created_at, id)`，Base64URL 编码为 `毫秒|uuid`。比较只看到毫秒；同一毫秒按 id 升序决定谁在前一页。`nextCursor` 指向本页最早的一条，下一页严格早于它，因此不会漏掉或重复同一时刻的两条。分页在已经做过来源检查的历史上升成，会话规模按单次对话而不是全库。不把游标当成客户端可改的时间范围。

**文档状态轮询。** `usePollingLoad` 在上一轮 `load` 结束前不发下一轮。期间如果计时器又到了，只记住“还要再跑一次”，结束后补一轮。组件卸载会清掉计时器，进行中的那轮返回后也不再 `setState`。这是为了索引和删除清理的状态，不是问答重试。

**失效来源。** 读历史和构建模型上下文走同一判断：文档已不存在（删除）或版本已撤回（本仓的禁用）时，该轮回答隐藏，问题改成固定提示，引用正文不返回。模型上下文再丢掉隐藏和未完成的轮次，最多保留最近三轮可见问答。续问不会把已撤回的内容当作事实。

**不用 SSE 自动重连。** 解析器忽略 `id` 和 `retry`。EventSource 的自动重连会再次 POST，可能重复计费，也可能在服务端已经保存回答之后再生成一条。所以恢复是显式的 GET。查询失败时保留本地编号，下次刷新再查；用户点停止才会清掉编号。`running` 会短轮询几次，仍然只 GET。

回归脚本：`node --experimental-strip-types scripts/reliability-regression.mjs`。它核对游标样例和“恢复不等于重发”。
