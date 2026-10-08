// 실제 학생 데이터·비밀 파일이 커밋되지 않게 막는다.
// 사용: node scripts/check-no-real-data.mjs [--staged]  (기본: git이 추적 중인 전체 파일 검사)
import { execFileSync } from "node:child_process";

const staged = process.argv.includes("--staged");
const args = staged
  ? ["diff", "--cached", "--name-only", "--diff-filter=ACMR"]
  : ["ls-files"];
const files = execFileSync("git", args, { encoding: "utf8" }).split("\n").filter(Boolean);

const forbidden = [
  [/^samples\/real\//, "실제 내보내기 파일(samples/real/)"],
  [/(^|\/)uploads\//, "업로드 보관 폴더"],
  [/\.(db|sqlite|sqlite3|db-journal)$/i, "데이터베이스 파일"],
  [/(^|\/)\.wrangler\//, "로컬 D1 상태(.wrangler/)"],
  [/(^|\/)\.env(\.|$)(?!example$)/, "환경 변수 파일"],
  [/(^|\/)\.dev\.vars$/, "Workers 비밀 파일"],
];

const hits = [];
for (const f of files) {
  for (const [re, why] of forbidden) if (re.test(f)) hits.push(`${f}  ← ${why}`);
}
if (hits.length) {
  console.error("커밋 금지 파일이 포함되어 있습니다:\n  " + hits.join("\n  "));
  console.error("실제 학생 데이터는 저장소에 넣지 않습니다. (HANDOFF.md §5)");
  process.exit(1);
}
console.log(`check-no-real-data: OK (${files.length} files)`);
