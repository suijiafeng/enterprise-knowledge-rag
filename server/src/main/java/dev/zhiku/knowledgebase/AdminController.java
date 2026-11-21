package dev.zhiku.knowledgebase;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IT_ADMIN 在调用方所属企业范围内的视图。用户本身由身份提供方管理，这里只读本地目录镜像。 */
@RestController
class AdminController {
  private final KnowledgeBaseService service;

  AdminController(KnowledgeBaseService service) {
    this.service = service;
  }

  @GetMapping("/api/admin/knowledge-bases")
  List<KnowledgeBaseRepository.AdminView> knowledgeBases() {
    return service.adminList();
  }

  @GetMapping("/api/admin/users")
  List<KnowledgeBaseRepository.DirectoryUser> users(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "limit", defaultValue = "20") int limit) {
    return service.searchUsers(q, limit);
  }
}
