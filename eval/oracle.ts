// 평가 정답 계산기. 엔진의 조회 코드와 독립적으로, 합성 학원 truth에서 직접 계산한다.
// (엔진과 같은 함수를 쓰면 엔진의 계산 오류를 잡을 수 없으므로 일부러 따로 구현)
import type { Academy } from "../fixtures/synthetic/academy";

export function oracle(a: Academy) {
  const student = (id: string) => a.students.find((s) => s.id === id)!;
  const held = (studentId: string, from: string, to: string) =>
    a.sessions.filter((s) => s.classId === student(studentId).classId && !s.cancelled && !s.future && s.date >= from && s.date <= to);

  function attendance(studentId: string, from: string, to: string) {
    const sess = held(studentId, from, to);
    const recs = sess.map((s) => a.attendance.find((x) => x.studentId === studentId && x.sessionId === s.id)!);
    return {
      held: sess.length,
      attended: recs.filter((x) => x.status !== "absent").length,
      late: recs.filter((x) => x.status === "late").length,
      absent: recs.filter((x) => x.status === "absent").length,
      absentDates: sess.filter((_, i) => recs[i]!.status === "absent").map((s) => s.date),
    };
  }

  function weekly(studentId: string): (number | null)[] {
    const st = student(studentId);
    return Array.from({ length: 8 }, (_, w) => a.scores.find((x) => x.studentId === studentId && x.testId === `${st.classId}-wk${w + 1}`)!.score);
  }

  function monthly(studentId: string): number | null {
    const st = student(studentId);
    return a.scores.find((x) => x.studentId === studentId && x.testId === `${st.classId}-mo9`)!.score;
  }

  function classAverage(testId: string): number {
    const xs = a.scores.filter((x) => x.testId === testId && x.score !== null).map((x) => x.score!);
    return Math.round((xs.reduce((p, c) => p + c, 0) / xs.length) * 10) / 10;
  }

  function homeworkAvg(studentId: string, fromWeek: number, toWeek: number): number {
    const xs = a.homework.filter((h) => h.studentId === studentId && h.week >= fromWeek && h.week <= toWeek).map((h) => h.completion);
    return Math.round(xs.reduce((p, c) => p + c, 0) / xs.length);
  }

  function homeworkMissedWeeks(studentId: string): number[] {
    return a.homework.filter((h) => h.studentId === studentId && h.completion === 0).map((h) => h.week);
  }

  /** 월례고사에서 틀린 문항이 가장 많은 단원(동률이면 모두) */
  function weakUnits(studentId: string): string[] {
    const st = student(studentId);
    const testId = `${st.classId}-mo9`;
    const wrong = new Map<string, number>();
    for (const r of a.itemResults.filter((x) => x.studentId === studentId && x.testId === testId && !x.correct)) {
      const unit = a.items.find((i) => i.testId === testId && i.no === r.no)!.unit;
      wrong.set(unit, (wrong.get(unit) ?? 0) + 1);
    }
    const max = Math.max(0, ...wrong.values());
    return [...wrong].filter(([, n]) => n === max && max > 0).map(([u]) => u);
  }

  return { student, attendance, weekly, monthly, classAverage, homeworkAvg, homeworkMissedWeeks, weakUnits };
}
