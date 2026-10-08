// 합성 학원 데이터를 생성한다: 정답 데이터(truth.json) + 지저분한 원천 파일 + 원천별 정답지.
// 실행: npm run gen:synthetic  → fixtures/synthetic/out/
//
// 원천 파일은 특정 학원 프로그램의 실제 형식을 흉내 낸 것이 아니다(실제 열 구성은 미확인, HANDOFF §2.7).
// 제목 행, 두 줄 헤더, 병합 셀, 합계·평균·정답·배점 행, 넓은 표, CP949 CSV, 섞인 표기처럼
// 현장 엑셀에서 흔한 "지저분함"을 골고루 넣어 가져오기 파이프라인을 시험하는 것이 목적이다.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import iconv from "iconv-lite";
import { AS_OF, NOW, SEED, buildAcademy, rng, type Academy, type AttendanceStatus, type Student } from "./academy";
import { colName, writeXlsx, type Cell } from "./xlsx-writer";

const OUT = join(dirname(fileURLToPath(import.meta.url)), "out");
const r = rng(SEED + 1); // 표기 흔들기 전용(정답 데이터와 독립)

/** 원천 파일 한 행의 정답 */
export interface RowAnswer {
  sheet: string;
  row: number; // 엑셀/CSV 기준 1부터
  outcome: "record" | "skip" | "needs_confirmation";
  /** record: 연결되어야 할 학생 / needs_confirmation: 후보 학생들 */
  studentId?: string;
  candidates?: string[];
  reason: string;
  /** 변환할 수 없어 경고와 함께 원문을 남겨야 하는 셀 */
  warnings?: { col: string; raw: string }[];
}
export interface FileAnswer {
  file: string;
  source: "roster" | "attendance" | "weekly_scores" | "item_results" | "items" | "homework" | "memos";
  encoding?: "utf-8" | "utf-8-bom" | "cp949";
  rows: RowAnswer[];
  /** 이 파일을 확정했을 때 잘못 합쳐진 학생이 0이려면 지켜야 할 것 */
  notes: string[];
}

function save(rel: string, data: Uint8Array | string) {
  const p = join(OUT, rel);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, data);
}

const md = (iso: string) => `${Number(iso.slice(5, 7))}/${Number(iso.slice(8, 10))}`;

function gradeVariant(g: number): string {
  return r.pick([`중${g}`, `${g}학년`, `중학교 ${g}학년`]);
}
function phoneVariant(p: string): string {
  const digits = p.replace(/-/g, "");
  return r.pick([p, digits, p.replace(/-/g, " "), `${digits.slice(0, 3)}.${digits.slice(3, 7)}.${digits.slice(7)}`]);
}

/** 같은 반 안에서 이름이 겹치는 학생(이름만으로는 자동 연결 금지) */
function sameNameInClass(a: Academy, s: Student): string[] {
  return a.students.filter((x) => x.classId === s.classId && x.name === s.name).map((x) => x.id);
}

function roster(a: Academy): FileAnswer {
  const rows: Cell[][] = [
    ["가람수학학원 원생 명단"],
    [`출력일: ${AS_OF}  /  재원생 ${a.students.length}명`],
    ["번호", "원생ID", "이름", "학년", "반", "학부모", "", "비고"],
    ["", "", "", "", "", "관계", "연락처", ""],
  ];
  const ans: RowAnswer[] = [
    { sheet: "원생명단", row: 1, outcome: "skip", reason: "제목 행" },
    { sheet: "원생명단", row: 2, outcome: "skip", reason: "설명 행" },
    { sheet: "원생명단", row: 3, outcome: "skip", reason: "헤더 1행(병합)" },
    { sheet: "원생명단", row: 4, outcome: "skip", reason: "헤더 2행" },
  ];
  a.students.forEach((s, i) => {
    const g = a.guardians.find((x) => x.id === s.guardianId)!;
    const cls = a.classes.find((c) => c.id === s.classId)!;
    const note = s.name === "박지아" ? "박지호 동생" : s.name === "박지호" ? "박지아 오빠" : "";
    rows.push([i + 1, s.externalId, s.name, gradeVariant(s.grade), r.pick([cls.name, cls.shortName]), g.relation, phoneVariant(g.phone), note]);
    ans.push({ sheet: "원생명단", row: rows.length, outcome: "record", studentId: s.id, reason: "원생ID로 신규 등록" });
  });
  rows.push(["※ 개인정보가 포함된 문서입니다. 외부 유출 주의"]);
  ans.push({ sheet: "원생명단", row: rows.length, outcome: "skip", reason: "안내 문구 행" });
  const merges = ["A1:H1", "A2:H2", "F3:G3", ...["A", "B", "C", "D", "E", "H"].map((c) => `${c}3:${c}4`)];
  save("files/원생명단.xlsx", writeXlsx([{ name: "원생명단", rows, merges }]));
  return {
    file: "원생명단.xlsx",
    source: "roster",
    rows: ans,
    notes: [
      "동명이인(김민준×2, 이서연×2)은 원생ID가 달라 각각 별도 학생이다.",
      "박지아·박지호는 학부모 연락처가 같지만 다른 학생이다. 연락처만으로 합치지 않는다.",
      "학년(중1/1학년/중학교 1학년)과 연락처(하이픈·공백·점·없음) 표기를 정규화한다.",
    ],
  };
}

const ATT_MARK: Record<AttendanceStatus, () => string> = {
  present: () => "O",
  late: () => r.pick(["지각", "△"]),
  absent: () => r.pick(["X", "결"]),
};

function attendance(a: Academy): FileAnswer {
  const ans: RowAnswer[] = [];
  const sheets = a.classes.map((cls) => {
    const sess = a.sessions.filter((s) => s.classId === cls.id && !s.future);
    const first = sess[0]!.date, last = sess[sess.length - 1]!.date;
    const name = `${cls.shortName}`;
    const rows: Cell[][] = [[`${cls.name} 출결부 (${md(first)}~${md(last)})`], ["번호", "이름", ...sess.map((s) => md(s.date)), "출석률"]];
    ans.push({ sheet: name, row: 1, outcome: "skip", reason: "제목 행" }, { sheet: name, row: 2, outcome: "skip", reason: "헤더(날짜가 열로 펼쳐짐)" });
    const studs = a.students.filter((s) => s.classId === cls.id);
    let weirdDone = false;
    studs.forEach((st, i) => {
      const warnings: RowAnswer["warnings"] = [];
      let held = 0, attended = 0;
      const cells: Cell[] = sess.map((s, j) => {
        if (s.cancelled) return "휴강";
        const at = a.attendance.find((x) => x.studentId === st.id && x.sessionId === s.id)!;
        held++;
        if (at.status !== "absent") attended++;
        // 변환할 수 없는 표기 1건: 경고와 함께 원문 보존 대상
        if (!weirdDone && cls.id === "c1" && i === 4 && j === 5) {
          weirdDone = true;
          warnings.push({ col: colName(2 + j), raw: "?" });
          return "?";
        }
        return ATT_MARK[at.status]();
      });
      rows.push([i + 1, st.name, ...cells, `${Math.round((attended / held) * 100)}%`]);
      const dup = sameNameInClass(a, st);
      ans.push(
        dup.length > 1
          ? { sheet: name, row: rows.length, outcome: "needs_confirmation", candidates: dup, reason: "같은 반 동명이인 — ID 없는 원천이라 이름·반으로 구분 불가", warnings }
          : { sheet: name, row: rows.length, outcome: "record", studentId: st.id, reason: "이름+반 일치(유일)", warnings },
      );
    });
    rows.push(["합계", "", ...sess.map((s) => (s.cancelled ? "" : a.attendance.filter((x) => x.sessionId === s.id && x.status !== "absent").length)), ""]);
    ans.push({ sheet: name, row: rows.length, outcome: "skip", reason: "합계 행" });
    rows.push(["※ 출석률은 지각을 출석으로 계산"]);
    ans.push({ sheet: name, row: rows.length, outcome: "skip", reason: "안내 문구 행" });
    return { name, rows, merges: [`A1:${colName(sess.length + 2)}1`] };
  });
  save("files/출결부_8월-10월.xlsx", writeXlsx(sheets));
  return {
    file: "출결부_8월-10월.xlsx",
    source: "attendance",
    rows: ans,
    notes: [
      "시트 이름이 반이다. 날짜 열(8/11 …)을 기록 하나씩으로 펼친다. 연도는 파일 맥락(2026)에서.",
      "표기: O=출석, 지각/△=지각, X/결=결석, 휴강=수업 없음(기록 아님).",
      "'?'는 변환 불가 — 버리지 말고 경고와 함께 원문 보존.",
      "출석률 열은 파생값이므로 가져오지 않는다(코드로 다시 계산).",
    ],
  };
}

function weeklyScores(a: Academy): FileAnswer {
  const name = "주간테스트";
  const rows: Cell[][] = [["2학기 주간테스트 성적"], [], ["학생명(반)", ...Array.from({ length: 8 }, (_, i) => `${i + 1}회`), "평균"]];
  const ans: RowAnswer[] = [
    { sheet: name, row: 1, outcome: "skip", reason: "제목 행" },
    { sheet: name, row: 2, outcome: "skip", reason: "빈 행" },
    { sheet: name, row: 3, outcome: "skip", reason: "헤더" },
  ];
  let oddDone = false;
  for (const cls of a.classes) {
    const studs = a.students.filter((s) => s.classId === cls.id);
    studs.forEach((st, i) => {
      const warnings: RowAnswer["warnings"] = [];
      const vals: number[] = [];
      const cells: Cell[] = Array.from({ length: 8 }, (_, w) => {
        const sc = a.scores.find((x) => x.studentId === st.id && x.testId === `${cls.id}-wk${w + 1}`)!;
        if (sc.score === null) return r.pick(["미응시", "결시", "-"]);
        vals.push(sc.score);
        if (!oddDone && cls.id === "c3" && i === 3 && w === 2) {
          oddDone = true;
          warnings.push({ col: colName(1 + w), raw: "재시험" });
          return "재시험";
        }
        return r.pick([`${sc.score}/100`, sc.score, `${sc.score}점`]);
      });
      const shown = st.name === "정하윤" ? "정하율" : st.name; // 이름 오타 1건
      rows.push([`${shown}(${cls.shortName})`, ...cells, Math.round(vals.reduce((p, c) => p + c, 0) / Math.max(1, vals.length))]);
      const dup = sameNameInClass(a, st);
      if (st.name === "정하윤")
        ans.push({ sheet: name, row: rows.length, outcome: "needs_confirmation", candidates: [st.id], reason: "이름 오타(정하율) — 비슷한 이름 후보로 확인 요청, 신규 학생 생성 금지", warnings });
      else if (dup.length > 1)
        ans.push({ sheet: name, row: rows.length, outcome: "needs_confirmation", candidates: dup, reason: "같은 반 동명이인", warnings });
      else ans.push({ sheet: name, row: rows.length, outcome: "record", studentId: st.id, reason: "이름+반 일치(유일)", warnings });
    });
    rows.push([`반평균(${cls.shortName})`, ...Array.from({ length: 8 }, () => ""), ""]);
    ans.push({ sheet: name, row: rows.length, outcome: "skip", reason: "반평균 행(데이터 아님)" });
  }
  save("files/주간테스트_성적.xlsx", writeXlsx([{ name, rows, merges: ["A1:J1"] }]));
  return {
    file: "주간테스트_성적.xlsx",
    source: "weekly_scores",
    rows: ans,
    notes: [
      "'이름(반)'을 분리한다. 점수 표기: 85/100, 85, 85점 → 85 (만점 100).",
      "미응시/결시/- → 응시하지 않음(0점이 아님).",
      "'재시험'은 변환 불가 — 경고와 함께 원문 보존.",
      "평균 열과 반평균 행은 가져오지 않는다.",
    ],
  };
}

function itemResults(a: Academy): FileAnswer {
  const header = ["학번", "이름", "반", ...Array.from({ length: 20 }, (_, i) => String(i + 1)), "총점"];
  const lines: string[][] = [header];
  const ans: RowAnswer[] = [{ sheet: "csv", row: 1, outcome: "skip", reason: "헤더" }];
  for (const cls of a.classes) {
    const test = a.tests.find((t) => t.classId === cls.id && t.kind === "monthly")!;
    const items = a.items.filter((x) => x.testId === test.id);
    lines.push(["정답", "", cls.shortName, ...items.map((x) => String(x.answer)), ""]);
    ans.push({ sheet: "csv", row: lines.length, outcome: "skip", reason: "정답 행" });
    lines.push(["배점", "", cls.shortName, ...items.map((x) => String(x.points)), "100"]);
    ans.push({ sheet: "csv", row: lines.length, outcome: "skip", reason: "배점 행" });
    for (const st of a.students.filter((s) => s.classId === cls.id)) {
      const sc = a.scores.find((x) => x.studentId === st.id && x.testId === test.id)!;
      const res = a.itemResults.filter((x) => x.studentId === st.id && x.testId === test.id);
      lines.push([st.omrNo, st.name, cls.shortName, ...(sc.score === null ? Array(20).fill("") : res.map((x) => (x.correct ? "O" : "X"))), sc.score === null ? "결시" : String(sc.score)]);
      const dup = sameNameInClass(a, st);
      ans.push(
        dup.length > 1
          ? { sheet: "csv", row: lines.length, outcome: "needs_confirmation", candidates: dup, reason: "학번은 있지만 첫 가져오기라 학번↔학생 연결이 없음 + 같은 반 동명이인. 확정 후 학번을 기억" }
          : { sheet: "csv", row: lines.length, outcome: "record", studentId: st.id, reason: "이름+반 일치(유일) → 학번 연결을 기억" },
      );
    }
  }
  const csv = lines.map((l) => l.join(",")).join("\r\n") + "\r\n";
  save("files/9월월례_문항정오.csv", iconv.encode(csv, "cp949"));
  return {
    file: "9월월례_문항정오.csv",
    source: "item_results",
    encoding: "cp949",
    rows: ans,
    notes: ["CP949 인코딩. 반마다 정답·배점 행이 데이터 앞에 있다.", "결시 행은 문항 칸이 비어 있다 → 응시하지 않음.", "학번 5자리는 채점 프로그램 식별자. 확정된 연결은 다음 가져오기에서 자동 매칭에 쓴다."],
  };
}

function itemInfo(a: Academy): FileAnswer {
  const name = "문항정보";
  const rows: Cell[][] = [["반", "문항", "단원", "유형", "배점"]];
  const ans: RowAnswer[] = [{ sheet: name, row: 1, outcome: "skip", reason: "헤더" }];
  const merges: string[] = [];
  for (const cls of a.classes) {
    const test = a.tests.find((t) => t.classId === cls.id && t.kind === "monthly")!;
    const items = a.items.filter((x) => x.testId === test.id);
    const startRow = rows.length + 1;
    items.forEach((it, i) => {
      rows.push([i === 0 ? cls.shortName : null, it.no, it.unit, it.type, it.points]);
      ans.push({ sheet: name, row: rows.length, outcome: "record", reason: `문항 정보(${cls.shortName} ${it.no}번) — 학생 데이터 아님` });
    });
    merges.push(`A${startRow}:A${rows.length}`);
  }
  save("files/문항정보_9월월례.xlsx", writeXlsx([{ name, rows, merges }]));
  return { file: "문항정보_9월월례.xlsx", source: "items", rows: ans, notes: ["반 열이 세로로 병합되어 있다 → 병합 값을 아래 행에 채운다."] };
}

function homework(a: Academy): FileAnswer {
  const lines: string[][] = [["원생ID", "이름", "반", ...Array.from({ length: 8 }, (_, i) => `${i + 1}주차`)]];
  const ans: RowAnswer[] = [{ sheet: "csv", row: 1, outcome: "skip", reason: "헤더" }];
  for (const st of a.students) {
    const cls = a.classes.find((c) => c.id === st.classId)!;
    const cells = Array.from({ length: 8 }, (_, w) => {
      const c = a.homework.find((h) => h.studentId === st.id && h.week === w + 1)!.completion;
      if (c === 100) return r.pick(["100%", "완료", "100"]);
      if (c === 0) return r.pick(["미제출", "0%", "X"]);
      return r.pick([`${c}%`, String(c)]);
    });
    lines.push([st.externalId, st.name, cls.shortName, ...cells]);
    ans.push({ sheet: "csv", row: lines.length, outcome: "record", studentId: st.id, reason: "원생ID 일치 (동명이인도 ID로 구분)" });
  }
  const csv = "﻿" + lines.map((l) => l.join(",")).join("\n") + "\n";
  save("files/숙제_수행.csv", csv);
  return { file: "숙제_수행.csv", source: "homework", encoding: "utf-8-bom", rows: ans, notes: ["UTF-8(BOM). 완료/100%/100 → 100, 미제출/0%/X → 0."] };
}

function memos(a: Academy): FileAnswer {
  const name = "메모";
  const rows: Cell[][] = [["날짜", "학생", "반", "메모", "작성"]];
  const ans: RowAnswer[] = [{ sheet: name, row: 1, outcome: "skip", reason: "헤더" }];
  for (const m of a.memos) {
    const st = a.students.find((s) => s.id === m.studentId)!;
    const cls = a.classes.find((c) => c.id === st.classId)!;
    const t = a.staff.find((s) => s.id === m.teacherId)!;
    const date: Cell = r.pick([{ date: m.date }, m.date.replace(/-/g, "."), md(m.date)]);
    rows.push([date, st.name, cls.shortName, m.text, t.name]);
    const dup = sameNameInClass(a, st);
    ans.push(
      dup.length > 1
        ? { sheet: name, row: rows.length, outcome: "needs_confirmation", candidates: dup, reason: "같은 반 동명이인" }
        : { sheet: name, row: rows.length, outcome: "record", studentId: st.id, reason: "이름+반 일치(유일)" },
    );
  }
  save("files/강사메모.xlsx", writeXlsx([{ name, rows }]));
  return { file: "강사메모.xlsx", source: "memos", rows: ans, notes: ["날짜 표기가 섞여 있다: 엑셀 날짜, 2026.09.03, 9/3(연도는 파일 맥락)."] };
}

function main() {
  const a = buildAcademy();
  save("truth.json", JSON.stringify({ generatedFrom: { seed: SEED, asOf: AS_OF, now: NOW }, ...a }, null, 1));
  const answers = [roster(a), attendance(a), weeklyScores(a), itemResults(a), itemInfo(a), homework(a), memos(a)];
  for (const f of answers) save(`answers/${f.file.replace(/\.(xlsx|csv)$/, "")}.json`, JSON.stringify(f, null, 1));

  const confirm = answers.flatMap((f) => f.rows.filter((x) => x.outcome === "needs_confirmation").map(() => f.file));
  console.log(`학생 ${a.students.length}명, 반 ${a.classes.length}개, 수업 ${a.sessions.length}회, 시험 ${a.tests.length}개`);
  console.log(`원천 파일 ${answers.length}개 → ${OUT}/files`);
  console.log(`확인 대기열로 가야 할 행: ${confirm.length}건`);
}

main();
