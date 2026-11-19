/** 脚手架用的中性、虚构示例数据。不从任何服务加载。 */
import type { IndexStatus } from "../../components/IndexStatusTag";

export interface OverviewData {
  updatedAt: string;
  metrics: { title: string; value: string; delta: string; trend: "up" | "down" | "none" | "bad" }[];
  dailyQuestions: { day: string; count: number }[];
  vectorStore: { label: string; value: string }[];
  recentDocuments: { name: string; chunks: number | null; status: IndexStatus; updatedAt: string }[];
  recentQuestions: { question: string; citations: number; askedAt: string }[];
}

export const overviewFixture: OverviewData = {
  updatedAt: "2026-11-19 11:36:08",
  metrics: [
    { title: "知识文档总数", value: "86", delta: "本周新增 2", trend: "up" },
    { title: "索引向量", value: "6,420", delta: "本周新增 148", trend: "up" },
    { title: "今日问答", value: "167", delta: "昨日 241", trend: "down" },
    { title: "回答率@5", value: "71.2%", delta: "较昨日低 2.4 个百分点", trend: "bad" },
  ],
  dailyQuestions: [
    { day: "11-13", count: 186 },
    { day: "11-14", count: 41 },
    { day: "11-15", count: 28 },
    { day: "11-16", count: 209 },
    { day: "11-17", count: 193 },
    { day: "11-18", count: 241 },
    { day: "11-19", count: 167 },
  ],
  vectorStore: [
    { label: "存储引擎", value: "PostgreSQL + pgvector" },
    { label: "向量维度", value: "待确认（ADR-002）" },
    { label: "索引类型", value: "待确认（先精确检索）" },
    { label: "文档总数", value: "86" },
    { label: "向量总数", value: "6,420" },
    { label: "最近同步", value: "2026-11-19 11:32:08" },
  ],
  recentDocuments: [
    { name: "员工手册 v3.2.pdf", chunks: 128, status: "indexed", updatedAt: "2026-11-18 16:42:11" },
    { name: "报销流程说明.md", chunks: 24, status: "indexed", updatedAt: "2026-11-18 09:17:44" },
    { name: "Q3 销售数据.xlsx", chunks: null, status: "parsing", updatedAt: "2026-11-17 15:03:29" },
    { name: "IT 安全规范.docx", chunks: 96, status: "indexed", updatedAt: "2026-11-17 11:21:06" },
    { name: "考勤制度.txt", chunks: 12, status: "indexed", updatedAt: "2026-11-14 18:05:52" },
  ],
  recentQuestions: [
    { question: "年假最多可以休几天?", citations: 3, askedAt: "2026-11-19 11:12:41" },
    { question: "报销审批需要几天?", citations: 2, askedAt: "2026-11-19 10:48:03" },
    { question: "试用期转正流程是什么?", citations: 4, askedAt: "2026-11-19 09:16:27" },
    { question: "VPN 怎么申请?", citations: 2, askedAt: "2026-11-18 17:26:55" },
    { question: "加班餐补标准?", citations: 3, askedAt: "2026-11-18 16:51:18" },
  ],
};
