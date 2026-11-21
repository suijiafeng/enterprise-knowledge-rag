import { EmptyState } from "./components/StateViews";

export function NoKnowledgeBase() {
  return <div className="page"><EmptyState icon="doc" title="没有可访问的知识库" description="当前账号没有任何知识库授权，或知识库列表尚未返回；请联系管理员授权后再试。" /></div>;
}
