import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { buildAcademy } from "../fixtures/synthetic/academy";
import type { EvalCase } from "../eval/types";

const a = buildAcademy();

describe("합성 학원", () => {
  it("규모: 학생 40명 안팎, 3개 반, 8주 기록", () => {
    expect(a.students.length).toBeGreaterThanOrEqual(36);
    expect(a.students.length).toBeLessThanOrEqual(44);
    expect(a.classes).toHaveLength(3);
    expect(Math.max(...a.sessions.filter((s) => !s.future).map((s) => s.week))).toBe(8);
  });

  it("함정: 동명이인 2쌍(한 쌍은 같은 반), 형제자매 1쌍", () => {
    const byName = new Map<string, typeof a.students>();
    for (const s of a.students) byName.set(s.name, [...(byName.get(s.name) ?? []), s]);
    const dups = [...byName.values()].filter((xs) => xs.length > 1);
    expect(dups).toHaveLength(2);
    expect(dups.some((xs) => xs[0]!.classId === xs[1]!.classId)).toBe(true);
    expect(dups.some((xs) => xs[0]!.classId !== xs[1]!.classId)).toBe(true);
    const byGuardian = new Map<string, number>();
    for (const s of a.students) byGuardian.set(s.guardianId, (byGuardian.get(s.guardianId) ?? 0) + 1);
    expect([...byGuardian.values()].filter((n) => n > 1)).toEqual([2]);
  });

  it("식별자는 모두 유일하다", () => {
    for (const key of ["id", "externalId", "omrNo"] as const) expect(new Set(a.students.map((s) => s[key])).size).toBe(a.students.length);
  });

  it("연락처는 실제 번호와 겹치지 않는 010-0000-xxxx 대역만 쓴다", () => {
    for (const g of a.guardians) expect(g.phone).toMatch(/^010-0000-\d{4}$/);
  });

  it("생성은 결정적이다", () => {
    expect(JSON.stringify(buildAcademy())).toBe(JSON.stringify(a));
  });
});

describe("평가 문장", () => {
  const dir = join(import.meta.dirname, "../eval/cases");
  const cases: EvalCase[] = readdirSync(dir)
    .filter((f) => f.endsWith(".jsonl"))
    .flatMap((f) => readFileSync(join(dir, f), "utf8").trim().split("\n").map((l) => JSON.parse(l) as EvalCase));
  const studentIds = new Set(a.students.map((s) => s.id));
  const slotIds = new Set(a.slots.map((s) => s.id));
  const docIds = new Set(a.knowledge.map((k) => k.id));
  const formIds = new Set(a.formTypes.map((f) => f.id));

  it("종류별 최소 수량", () => {
    const n = (c: string) => cases.filter((x) => x.category === c).length;
    expect(n("answer")).toBeGreaterThanOrEqual(100);
    expect(n("lookup")).toBeGreaterThanOrEqual(100);
    expect(n("task")).toBeGreaterThanOrEqual(100);
    expect(n("scope")).toBeGreaterThanOrEqual(50);
    expect(n("attack")).toBeGreaterThanOrEqual(50);
  });

  it("ID가 유일하고 참조가 모두 존재한다", () => {
    expect(new Set(cases.map((c) => c.id)).size).toBe(cases.length);
    for (const c of cases) {
      if (c.principal.kind === "guardian") for (const id of c.principal.subjectIds) expect(studentIds.has(id), c.id).toBe(true);
      for (const id of c.expect.forbiddenSubjects ?? []) expect(studentIds.has(id), c.id).toBe(true);
      for (const id of c.expect.cites ?? []) expect(docIds.has(id), c.id).toBe(true);
      const f = c.expect.form;
      if (f) {
        expect(formIds.has(f.type), c.id).toBe(true);
        for (const v of [f.prefill?.slot, f.executed?.slot]) if (v) expect(slotIds.has(v), c.id).toBe(true);
      }
    }
  });

  it("학부모 자신의 자녀는 금지 대상에 들어가지 않는다", () => {
    for (const c of cases) {
      if (c.principal.kind !== "guardian") continue;
      for (const id of c.principal.subjectIds) expect(c.expect.forbiddenSubjects ?? [], c.id).not.toContain(id);
    }
  });
});
