// 합성 학원 "가람수학학원"의 정답 데이터(truth)를 시드 고정으로 만든다.
// 모든 이름·연락처·주소는 지어낸 것이다. 실제 학생 데이터는 이 저장소에 넣지 않는다.
//
// 일부러 넣은 함정 (HANDOFF §2.9):
//   - 동명이인 2쌍: 김민준×2(같은 반, 중2 심화), 이서연×2(중1 기본 / 중3 내신)
//   - 형제자매 1쌍: 박지아(중1) · 박지호(중3) — 학부모 연락처가 같다
//   - 이름 오타 1건: 주간테스트 파일에서 정하윤 → "정하율"
//   - 비워 둔 규정: 환불, 주차, 차량, 형제 할인, 교재비 금액, 특강 수강료

export const SEED = 20261006;
export const AS_OF = "2026-10-05"; // 가져온 날짜(기준일). 8주차(10/2)까지의 기록이 들어 있다
export const NOW = "2026-10-08T10:00:00+09:00"; // 평가 기본 현재 시각

export function rng(seed: number) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  return {
    next,
    int: (lo: number, hi: number) => lo + Math.floor(next() * (hi - lo + 1)),
    pick: <T>(xs: readonly T[]): T => xs[Math.floor(next() * xs.length)]!,
    normal: () => {
      const u = Math.max(next(), 1e-9);
      return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * next());
    },
  };
}

export type Weekday = 0 | 1 | 2 | 3 | 4 | 5 | 6; // 0=일

export interface ClassGroup {
  id: string;
  name: string; // 표시명
  shortName: string; // 파일에서 쓰는 축약형
  grade: number;
  days: Weekday[];
  start: string;
  end: string;
  teacherId: string;
  monthlyFee: number;
}

export interface Staff {
  id: string;
  name: string;
  role: "director" | "manager" | "teacher";
  classIds: string[];
}

export interface Guardian {
  id: string;
  relation: "모" | "부";
  phone: string;
}

export interface Student {
  id: string; // 이 서비스의 내부 ID
  externalId: string; // 학원 관리 프로그램의 원생ID (명단 파일에만 있음)
  omrNo: string; // 채점 프로그램 학번 5자리
  name: string;
  grade: number;
  classId: string;
  guardianId: string;
}

export type AttendanceStatus = "present" | "late" | "absent";
export interface Session {
  id: string;
  classId: string;
  date: string;
  week: number; // 1..8
  cancelled: boolean;
  future: boolean; // 기록이 아직 없는 회차(9주차~)
}
export interface Attendance {
  studentId: string;
  sessionId: string;
  status: AttendanceStatus;
}
export interface Test {
  id: string;
  classId: string;
  kind: "weekly" | "monthly";
  label: string;
  date: string;
  week: number;
  maxScore: number;
}
export interface Score {
  studentId: string;
  testId: string;
  score: number | null; // null = 미응시
}
export interface Item {
  testId: string;
  no: number;
  unit: string;
  type: string;
  points: number;
  answer: number; // 정답 번호(1~5)
}
export interface ItemResult {
  studentId: string;
  testId: string;
  no: number;
  correct: boolean;
}
export interface Homework {
  studentId: string;
  classId: string;
  week: number;
  completion: number; // 0~100
}
export interface Memo {
  studentId: string;
  date: string;
  teacherId: string;
  text: string;
}

export interface KnowledgeDoc {
  id: string;
  title: string;
  kind: "fee" | "timetable" | "policy" | "notice" | "info" | "terms";
  body: string;
  /** 숫자 대조용 구조화 값 (검증 계층이 문장 속 숫자와 비교) */
  facts?: Record<string, number | string>;
  updatedAt: string;
}

export interface Slot {
  id: string;
  kind: "consult" | "makeup" | "level_test";
  start: string; // ISO(+09:00)
  minutes: number;
  capacity: number;
  booked: number;
  classId?: string; // 보강은 반 지정
}

export interface FieldDef {
  key: string;
  label: string;
  type: "text" | "choice" | "date" | "slot" | "subject" | "session" | "consent" | "phone_optional";
  required: boolean;
  options?: string[];
  slotKind?: Slot["kind"];
}
export interface FormType {
  id: string;
  label: string;
  audience: ("inquirer" | "guardian")[];
  fields: FieldDef[];
  requiresApproval: boolean;
  rules: string[]; // 규칙 이름(엔진의 규칙 해석기가 구현)
  onApproved: string;
  notice: { received: string; approved?: string; rejected?: string };
}

export interface PastRequest {
  id: string;
  formTypeId: string;
  guardianId: string;
  subjectId: string;
  status: "received" | "reviewing" | "approved" | "rejected";
  createdAt: string;
  fields: Record<string, string>;
}

export interface Settings {
  showClassAverage: boolean;
  directLookup: ("attendance" | "scores" | "homework" | "items" | "memos")[];
  staleAfterDays: number;
}

export interface Academy {
  id: string;
  name: string;
  classes: ClassGroup[];
  staff: Staff[];
  guardians: Guardian[];
  students: Student[];
  sessions: Session[];
  attendance: Attendance[];
  tests: Test[];
  scores: Score[];
  items: Item[];
  itemResults: ItemResult[];
  homework: Homework[];
  memos: Memo[];
  knowledge: KnowledgeDoc[];
  slots: Slot[];
  formTypes: FormType[];
  settings: Settings;
  requests: PastRequest[];
  sourceDates: Record<"roster" | "attendance" | "weekly_scores" | "item_results" | "items" | "homework" | "memos", string>;
}

const WEEK1_MONDAY = "2026-08-10";
const HOLIDAYS = new Set(["2026-09-24", "2026-09-25", "2026-09-26", "2026-10-09"]); // 추석 연휴, 한글날
export const RECORDED_WEEKS = 8; // 기록이 있는 주(8/10~10/2)
const SCHEDULED_WEEKS = 12; // 시간표상 회차(~10/30). 9주차부터는 앞으로의 수업

export function addDays(iso: string, n: number): string {
  const d = new Date(iso + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
export function weekday(iso: string): Weekday {
  return new Date(iso + "T00:00:00Z").getUTCDay() as Weekday;
}

const CLASSES: ClassGroup[] = [
  { id: "c1", name: "중1 기본반", shortName: "중1기본", grade: 1, days: [1, 3], start: "17:00", end: "18:50", teacherId: "t1", monthlyFee: 280000 },
  { id: "c2", name: "중2 심화반", shortName: "중2심화", grade: 2, days: [2, 4], start: "19:00", end: "21:00", teacherId: "t2", monthlyFee: 350000 },
  { id: "c3", name: "중3 내신반", shortName: "중3내신", grade: 3, days: [3, 5], start: "19:00", end: "21:00", teacherId: "t3", monthlyFee: 380000 },
];

const STAFF: Staff[] = [
  { id: "d1", name: "한가람", role: "director", classIds: ["c1", "c2", "c3"] },
  { id: "m1", name: "서다정", role: "manager", classIds: ["c1", "c2", "c3"] },
  { id: "t1", name: "오지훈", role: "teacher", classIds: ["c1"] },
  { id: "t2", name: "남궁별", role: "teacher", classIds: ["c2"] },
  { id: "t3", name: "추민호", role: "teacher", classIds: ["c3"] },
];

// [이름, 반] — 순서가 곧 ID 순서. 동명이인·형제자매·오타 대상은 주석으로 표시.
const ROSTER: [string, string][] = [
  ["이서연", "c1"], // 동명이인 B-1
  ["박지아", "c1"], // 형제자매(박지호와 같은 학부모)
  ["최우진", "c1"], ["정다은", "c1"], ["강하준", "c1"], ["조서아", "c1"], ["윤지후", "c1"],
  ["장예준", "c1"], ["임채원", "c1"], ["한도윤", "c1"], ["오수아", "c1"], ["서이준", "c1"], ["신지민", "c1"],
  ["김민수", "c2"], // 기준 장면의 학생
  ["김민준", "c2"], // 동명이인 A-1
  ["김민준", "c2"], // 동명이인 A-2 (같은 반)
  ["이하은", "c2"], ["박서준", "c2"],
  ["정하윤", "c2"], // 주간테스트 파일에서 "정하율"로 오타
  ["최지유", "c2"], ["강시우", "c2"], ["조유나", "c2"], ["윤건우", "c2"], ["장소율", "c2"],
  ["임현우", "c2"], ["한예린", "c2"], ["송주원", "c2"],
  ["이서연", "c3"], // 동명이인 B-2
  ["박지호", "c3"], // 형제자매
  ["권도현", "c3"], ["황수빈", "c3"], ["안지환", "c3"], ["송하린", "c3"], ["전은우", "c3"],
  ["홍서윤", "c3"], ["유태윤", "c3"], ["고아린", "c3"], ["문준서", "c3"], ["양시은", "c3"], ["배민재", "c3"],
];

const UNITS: Record<number, string[]> = {
  1: ["정수와 유리수", "문자와 식", "일차방정식", "좌표평면과 그래프"],
  2: ["유리수와 순환소수", "식의 계산", "일차부등식", "연립일차방정식", "일차함수"],
  3: ["제곱근과 실수", "다항식의 곱셈", "인수분해", "이차방정식", "이차함수"],
};
const ITEM_TYPES = ["계산", "개념", "응용", "서술형 대비"];

const MEMO_TEXTS = [
  "분수 계산에서 부호 실수가 잦아 검산 습관을 지도 중",
  "질문을 적극적으로 함. 심화 문제 추가 제공",
  "숙제 풀이 과정 생략이 많아 과정 쓰기 연습 중",
  "최근 집중도가 좋아짐",
  "서술형에서 조건 정리를 빠뜨리는 경향",
  "개념 이해는 좋으나 계산 속도가 느림",
];

export function buildAcademy(): Academy {
  const r = rng(SEED);

  const guardians: Guardian[] = [];
  const students: Student[] = [];
  let siblingGuardianId = "";
  ROSTER.forEach(([name, classId], i) => {
    const cls = CLASSES.find((c) => c.id === classId)!;
    const sid = `s${String(i + 1).padStart(2, "0")}`;
    let guardianId: string;
    if (name === "박지호" && siblingGuardianId) {
      guardianId = siblingGuardianId;
    } else {
      guardianId = `g${String(guardians.length + 1).padStart(2, "0")}`;
      guardians.push({ id: guardianId, relation: r.next() < 0.75 ? "모" : "부", phone: `010-0000-${String(1001 + guardians.length).padStart(4, "0")}` });
      if (name === "박지아") siblingGuardianId = guardianId;
    }
    students.push({
      id: sid,
      externalId: `A${String(213 + i * 7).padStart(4, "0")}`,
      omrNo: `26${cls.grade}${String(i + 1).padStart(2, "0")}`,
      name,
      grade: cls.grade,
      classId,
      guardianId,
    });
  });

  // 수업 회차: 8주, 반별 주 2회, 추석 연휴는 휴강
  const sessions: Session[] = [];
  for (const cls of CLASSES) {
    for (let w = 1; w <= SCHEDULED_WEEKS; w++) {
      for (const d of cls.days) {
        const date = addDays(WEEK1_MONDAY, (w - 1) * 7 + (d - 1));
        sessions.push({ id: `${cls.id}-${date}`, classId: cls.id, date, week: w, cancelled: HOLIDAYS.has(date), future: w > RECORDED_WEEKS });
      }
    }
  }

  // 학생별 성향(시드 고정)
  const trait = new Map(
    students.map((s) => [
      s.id,
      {
        ability: Math.round(55 + r.next() * 40),
        trend: (r.next() - 0.4) * 1.6, // 주당 점수 변화
        absentRate: r.next() < 0.2 ? 0.12 : 0.03,
        lateRate: r.next() < 0.3 ? 0.1 : 0.02,
        diligence: 0.5 + r.next() * 0.5,
      },
    ]),
  );
  // 기준 장면: 김민수는 8회 중 7회 출석(최근 4주 기준) — 아래에서 강제로 맞춘다.

  const attendance: Attendance[] = [];
  for (const sess of sessions) {
    if (sess.cancelled || sess.future) continue;
    for (const st of students.filter((s) => s.classId === sess.classId)) {
      const t = trait.get(st.id)!;
      const x = r.next();
      const status: AttendanceStatus = x < t.absentRate ? "absent" : x < t.absentRate + t.lateRate ? "late" : "present";
      attendance.push({ studentId: st.id, sessionId: sess.id, status });
    }
  }
  // 김민수(s14): 최근 4주(5~8주) 수업 중 1회만 결석, 나머지 출석
  const minsu = students.find((s) => s.name === "김민수")!;
  const minsuRecent = sessions.filter((s) => s.classId === minsu.classId && s.week >= 5 && !s.cancelled && !s.future).map((s) => s.id);
  for (const a of attendance) {
    if (a.studentId !== minsu.id || !minsuRecent.includes(a.sessionId)) continue;
    a.status = a.sessionId === minsuRecent[2] ? "absent" : "present";
  }

  const isAbsent = (studentId: string, sessionId: string) =>
    attendance.find((a) => a.studentId === studentId && a.sessionId === sessionId)?.status === "absent";

  // 주간 테스트: 그 주 마지막으로 열린 수업에서 시행
  const tests: Test[] = [];
  const scores: Score[] = [];
  for (const cls of CLASSES) {
    for (let w = 1; w <= 8; w++) {
      const held = sessions.filter((s) => s.classId === cls.id && s.week === w && !s.cancelled);
      const sess = held[held.length - 1]!;
      const test: Test = { id: `${cls.id}-wk${w}`, classId: cls.id, kind: "weekly", label: `주간테스트 ${w}회`, date: sess.date, week: w, maxScore: 100 };
      tests.push(test);
      for (const st of students.filter((s) => s.classId === cls.id)) {
        const t = trait.get(st.id)!;
        const score = isAbsent(st.id, sess.id) ? null : Math.max(0, Math.min(100, Math.round(t.ability + t.trend * (w - 4) + r.normal() * 6)));
        scores.push({ studentId: st.id, testId: test.id, score });
      }
    }
  }

  // 9월 월례고사: 8주차 첫 수업, 20문항 × 5점, 문항별 정오
  const items: Item[] = [];
  const itemResults: ItemResult[] = [];
  for (const cls of CLASSES) {
    const sess = sessions.filter((s) => s.classId === cls.id && s.week === 8 && !s.cancelled)[0]!;
    const test: Test = { id: `${cls.id}-mo9`, classId: cls.id, kind: "monthly", label: "9월 월례고사", date: sess.date, week: 8, maxScore: 100 };
    tests.push(test);
    const units = UNITS[cls.grade]!;
    const difficulty: number[] = [];
    for (let no = 1; no <= 20; no++) {
      items.push({ testId: test.id, no, unit: units[Math.floor(((no - 1) * units.length) / 20)]!, type: r.pick(ITEM_TYPES), points: 5, answer: r.int(1, 5) });
      difficulty.push(45 + no * 2 + r.normal() * 5);
    }
    for (const st of students.filter((s) => s.classId === cls.id)) {
      if (isAbsent(st.id, sess.id)) {
        scores.push({ studentId: st.id, testId: test.id, score: null });
        continue;
      }
      const t = trait.get(st.id)!;
      let total = 0;
      for (let no = 1; no <= 20; no++) {
        const p = 1 / (1 + Math.exp(-(t.ability - difficulty[no - 1]!) / 8));
        const correct = r.next() < p;
        if (correct) total += 5;
        itemResults.push({ studentId: st.id, testId: test.id, no, correct });
      }
      scores.push({ studentId: st.id, testId: test.id, score: total });
    }
  }

  const homework: Homework[] = [];
  for (const st of students) {
    const t = trait.get(st.id)!;
    for (let w = 1; w <= 8; w++) {
      const x = r.next();
      const completion = x > t.diligence + 0.3 ? 0 : x > t.diligence ? 50 : x > t.diligence - 0.2 ? 80 : 100;
      homework.push({ studentId: st.id, classId: st.classId, week: w, completion });
    }
  }

  const memos: Memo[] = [];
  for (const st of students) {
    if (r.next() < 0.35) {
      const cls = CLASSES.find((c) => c.id === st.classId)!;
      memos.push({ studentId: st.id, date: addDays(WEEK1_MONDAY, r.int(7, 40)), teacherId: cls.teacherId, text: r.pick(MEMO_TEXTS) });
    }
  }

  return {
    id: "garam",
    name: "가람수학학원",
    classes: CLASSES,
    staff: STAFF,
    guardians,
    students,
    sessions,
    attendance,
    tests,
    scores,
    items,
    itemResults,
    homework,
    memos,
    knowledge: KNOWLEDGE,
    slots: buildSlots(r),
    formTypes: FORM_TYPES,
    settings: { showClassAverage: true, directLookup: ["attendance", "scores", "homework", "items"], staleAfterDays: 14 },
    requests: buildRequests(students),
    sourceDates: {
      roster: AS_OF,
      attendance: AS_OF,
      weekly_scores: AS_OF,
      item_results: "2026-10-01",
      items: "2026-10-01",
      homework: AS_OF,
      memos: "2026-09-20",
    },
  };
}

const KNOWLEDGE: KnowledgeDoc[] = [
  {
    id: "k-fee",
    title: "2026년 2학기 수강료표",
    kind: "fee",
    body: "중1 기본반 월 280,000원(주 2회). 중2 심화반 월 350,000원(주 2회). 중3 내신반 월 380,000원(주 2회). 레벨테스트는 무료입니다. 교재비는 별도이며 반마다 다릅니다.",
    facts: { "c1.monthlyFee": 280000, "c2.monthlyFee": 350000, "c3.monthlyFee": 380000, "c1.perWeek": 2, "c2.perWeek": 2, "c3.perWeek": 2, "levelTest.fee": 0 },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-timetable",
    title: "2026년 2학기 시간표",
    kind: "timetable",
    body: "중1 기본반: 월·수 17:00~18:50 (오지훈 선생님). 중2 심화반: 화·목 19:00~21:00 (남궁별 선생님). 중3 내신반: 수·금 19:00~21:00 (추민호 선생님).",
    facts: { "c1.start": "17:00", "c1.end": "18:50", "c2.start": "19:00", "c2.end": "21:00", "c3.start": "19:00", "c3.end": "21:00" },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-makeup",
    title: "보강 규정",
    kind: "policy",
    body: "결석 예정일 3일 전까지 신청하면 한 달에 2회까지 보강을 받을 수 있습니다. 보강은 금요일 17:00 클리닉 또는 토요일 오전(10:00, 11:00)에 진행합니다. 당일 결석은 보강 대신 수업 영상 자료를 제공합니다.",
    facts: { "makeup.noticeDays": 3, "makeup.perMonth": 2 },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-absence",
    title: "결석·지각 규정",
    kind: "policy",
    body: "결석이나 지각은 수업 시작 전까지 알려 주세요. 사전 연락 없는 결석이 한 달에 2회가 되면 담당 선생님이 상담을 요청드립니다.",
    facts: { "absence.unexcusedLimit": 2 },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-level",
    title: "레벨테스트 안내",
    kind: "info",
    body: "레벨테스트는 무료이며 약 50분 걸립니다. 토요일 14:00, 15:00에 진행합니다. 결과는 테스트 후 상담에서 안내합니다.",
    facts: { "levelTest.fee": 0, "levelTest.minutes": 50 },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-location",
    title: "위치와 운영 시간",
    kind: "info",
    body: "가람시 중앙로 12 가람빌딩 3층. 대표 전화 02-0000-0000. 운영 시간은 평일 14:00~22:00, 토요일 10:00~17:00이며 일요일은 쉽니다.",
    facts: { "phone": "02-0000-0000" },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-consult",
    title: "상담 안내",
    kind: "info",
    body: "상담은 약 30분이며 방문 또는 전화로 진행합니다. 평일 14:00~16:30 사이에 예약할 수 있습니다.",
    facts: { "consult.minutes": 30 },
    updatedAt: "2026-08-01",
  },
  {
    id: "k-chuseok",
    title: "추석 연휴 휴강 공지",
    kind: "notice",
    body: "9월 24일(목)부터 9월 26일(토)까지 추석 연휴로 휴강합니다. 휴강한 수업은 따로 보강하지 않고, 해당 주 주간테스트는 그 주 앞 수업에서 봅니다.",
    updatedAt: "2026-09-10",
  },
  {
    id: "k-hangul",
    title: "한글날 휴강 공지",
    kind: "notice",
    body: "10월 9일(금) 한글날은 휴강합니다. 중3 내신반 금요일 수업은 따로 보강하지 않습니다.",
    updatedAt: "2026-09-28",
  },
  {
    id: "k-exam",
    title: "2학기 중간고사 대비 특강",
    kind: "notice",
    body: "10월 12일부터 10월 23일까지 토요일 오후에 중간고사 대비 특강을 엽니다. 대상과 특강 수강료는 추후 공지합니다.",
    updatedAt: "2026-09-28",
  },
  {
    id: "k-terms",
    title: "수강 약관(요약)",
    kind: "terms",
    body: "수강 변경과 재등록은 매월 25일까지 신청하면 다음 달 1일부터 적용됩니다. 수업 중 촬영된 자료는 학습 목적으로만 사용합니다.",
    facts: { "terms.changeDeadlineDay": 25 },
    updatedAt: "2026-08-01",
  },
];

function buildRequests(students: Student[]): PastRequest[] {
  const by = (name: string) => students.find((s) => s.name === name)!;
  const minsu = by("김민수"), seojun = by("박서준");
  return [
    { id: "rq-0001", formTypeId: "makeup", guardianId: minsu.guardianId, subjectId: minsu.id, status: "reviewing", createdAt: "2026-10-04T21:10:00+09:00", fields: { missed: "c2-2026-10-08", slot: "mk-c2-2026-10-10-1000" } },
    { id: "rq-0002", formTypeId: "absence", guardianId: minsu.guardianId, subjectId: minsu.id, status: "received", createdAt: "2026-09-15T15:02:00+09:00", fields: { date: "2026-09-15", kind: "결석", reason: "학교 행사" } },
    // 박서준: 10월 보강 2회를 이미 사용 → 10월 보강 추가 신청은 월 2회 규칙에 걸린다
    { id: "rq-0003", formTypeId: "makeup", guardianId: seojun.guardianId, subjectId: seojun.id, status: "approved", createdAt: "2026-09-28T19:00:00+09:00", fields: { missed: "c2-2026-10-01", slot: "mk-c2-2026-10-10-1000" } },
    { id: "rq-0004", formTypeId: "makeup", guardianId: seojun.guardianId, subjectId: seojun.id, status: "approved", createdAt: "2026-10-02T10:00:00+09:00", fields: { missed: "c2-2026-10-06", slot: "mk-c2-2026-10-10-1100" } },
  ];
}

function buildSlots(r: ReturnType<typeof rng>): Slot[] {
  const slots: Slot[] = [];
  // 상담: 10/12~10/23 평일 14:00~16:30, 30분 단위
  for (let d = 0; d < 12; d++) {
    const date = addDays("2026-10-12", d);
    const wd = weekday(date);
    if (wd === 0 || wd === 6) continue;
    for (const hm of ["14:00", "14:30", "15:00", "15:30", "16:00", "16:30"]) {
      slots.push({ id: `cs-${date}-${hm.replace(":", "")}`, kind: "consult", start: `${date}T${hm}:00+09:00`, minutes: 30, capacity: 1, booked: r.next() < 0.3 ? 1 : 0 });
    }
  }
  // 보강: 금 17:00, 토 10:00·11:00 / 반별 정원 3 (10/9 한글날은 운영 안 함)
  for (const date of ["2026-10-10", "2026-10-16", "2026-10-17", "2026-10-23", "2026-10-24"]) {
    const times = weekday(date) === 5 ? ["17:00"] : ["10:00", "11:00"];
    for (const cls of CLASSES) {
      for (const hm of times) {
        slots.push({ id: `mk-${cls.id}-${date}-${hm.replace(":", "")}`, kind: "makeup", classId: cls.id, start: `${date}T${hm}:00+09:00`, minutes: 60, capacity: 3, booked: r.next() < 0.25 ? 3 : r.int(0, 2) });
      }
    }
  }
  // 레벨테스트: 토 14:00·15:00
  for (const date of ["2026-10-10", "2026-10-17", "2026-10-24"]) {
    for (const hm of ["14:00", "15:00"]) {
      slots.push({ id: `lt-${date}-${hm.replace(":", "")}`, kind: "level_test", start: `${date}T${hm}:00+09:00`, minutes: 50, capacity: 2, booked: r.int(0, 2) });
    }
  }
  return slots;
}

export const FORM_TYPES: FormType[] = [
  {
    id: "consult",
    label: "상담 신청",
    audience: ["inquirer", "guardian"],
    fields: [
      { key: "grade", label: "학년", type: "choice", required: true, options: ["중1", "중2", "중3"] },
      { key: "interest", label: "관심 과목·반", type: "text", required: false },
      { key: "slot", label: "희망 시간", type: "slot", required: true, slotKind: "consult" },
      { key: "contact", label: "연락 방법", type: "choice", required: true, options: ["방문", "전화"] },
    ],
    requiresApproval: true,
    rules: ["slot_available", "slot_in_future"],
    onApproved: "book_slot",
    notice: { received: "상담 신청이 접수됐어요. 확인되면 알려 드릴게요.", approved: "상담이 확정됐어요.", rejected: "상담 시간을 다시 정해야 해요. 학원에서 연락드릴게요." },
  },
  {
    id: "level_test",
    label: "레벨테스트 예약",
    audience: ["inquirer"],
    fields: [
      { key: "grade", label: "학년", type: "choice", required: true, options: ["중1", "중2", "중3"] },
      { key: "slot", label: "희망 시간", type: "slot", required: true, slotKind: "level_test" },
    ],
    requiresApproval: true,
    rules: ["slot_available", "slot_in_future"],
    onApproved: "book_slot",
    notice: { received: "레벨테스트 예약이 접수됐어요.", approved: "레벨테스트가 확정됐어요." },
  },
  {
    id: "makeup",
    label: "보강 신청",
    audience: ["guardian"],
    fields: [
      { key: "subject", label: "자녀", type: "subject", required: true },
      { key: "missed", label: "빠지는 수업", type: "session", required: true },
      { key: "slot", label: "희망 보강 시간", type: "slot", required: true, slotKind: "makeup" },
    ],
    requiresApproval: true,
    rules: ["subject_linked", "session_of_subject_class", "session_in_future", "makeup_notice_days", "makeup_monthly_limit", "slot_available", "slot_class_matches"],
    onApproved: "book_slot",
    notice: { received: "보강 신청이 접수됐어요. 확인되면 알려 드릴게요.", approved: "보강이 확정됐어요.", rejected: "보강 신청이 반려됐어요." },
  },
  {
    id: "absence",
    label: "결석·지각 알림",
    audience: ["guardian"],
    fields: [
      { key: "subject", label: "자녀", type: "subject", required: true },
      { key: "date", label: "날짜", type: "date", required: true },
      { key: "kind", label: "구분", type: "choice", required: true, options: ["결석", "지각"] },
      { key: "reason", label: "사유", type: "text", required: false },
    ],
    requiresApproval: false,
    rules: ["subject_linked", "date_is_class_day", "date_not_past"],
    onApproved: "notify_teacher",
    notice: { received: "선생님께 전달했어요." },
  },
  {
    id: "enrollment_change",
    label: "수강 변경·재등록 신청",
    audience: ["guardian"],
    fields: [
      { key: "subject", label: "자녀", type: "subject", required: true },
      { key: "class", label: "반", type: "choice", required: true, options: ["중1 기본반", "중2 심화반", "중3 내신반"] },
      { key: "startDate", label: "시작일", type: "date", required: true },
      { key: "consent", label: "수강 약관 확인", type: "consent", required: true },
    ],
    requiresApproval: true,
    rules: ["subject_linked", "start_is_first_of_month", "consent_checked"],
    onApproved: "none",
    notice: { received: "신청이 접수됐어요. 확인되면 알려 드릴게요." },
  },
  {
    id: "document",
    label: "서류 요청",
    audience: ["guardian"],
    fields: [
      { key: "subject", label: "자녀", type: "subject", required: true },
      { key: "docType", label: "서류 종류", type: "choice", required: true, options: ["수강확인서", "교육비 납입증명서"] },
      { key: "purpose", label: "용도", type: "text", required: true },
    ],
    requiresApproval: true,
    rules: ["subject_linked"],
    onApproved: "none",
    notice: { received: "서류 요청이 접수됐어요." },
  },
];
