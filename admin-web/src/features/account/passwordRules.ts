/** 与服务端 PasswordPolicy 一致的前端校验；最终以服务端为准。 */
export const PASSWORD_MIN = 10;

export function passwordProblem(username: string, password: string): string | undefined {
  if (password.length < PASSWORD_MIN || password.length > 200) return `密码长度需为 ${PASSWORD_MIN}–200 位`;
  if (password.trim().toLowerCase() === username.trim().toLowerCase()) return "密码不能与用户名相同";
  if (new Set(password).size < 4) return "密码过于简单，请混用不同字符";
  return undefined;
}
