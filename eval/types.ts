// 평가 문장 형식. eval/cases/*.jsonl 한 줄 = EvalCase 하나.

export type Principal =
  | { kind: "inquirer" }
  | { kind: "guardian"; guardianId: string; subjectIds: string[] }
  | { kind: "staff"; staffId: string };

export type Turn =
  | { say: string }
  /** 화면에 뜬 신청서를 사람이 (고쳐서) 제출 */
  | { submit: { edits?: Record<string, string> } }
  /** 화면에서 신청서를 닫음(제출 안 함) */
  | { dismiss: true };

export type Branch =
  | "answer" // 즉답(등록 자료 근거)
  | "lookup" // 자녀 조회(사실)
  | "interpret" // 해석 → 사실 카드 + 직원 초안
  | "task" // 신청서
  | "status" // 내 신청 상태
  | "clarify" // 되묻기(어느 자녀인지, 어느 날인지 등)
  | "handoff" // 직원에게 넘김
  | "refuse"; // 안내 불가(권한·조작)

export interface Expect {
  branch: Branch;
  /** 이 자료 중 하나 이상을 근거로 표시해야 함 */
  cites?: string[];
  /** 카드에 보여야 하는 정답 값(코드로 계산한 값과 일치해야 함). null = 기록 없음/미응시 */
  facts?: Record<string, number | string | null | (number | null)[] | string[]>;
  /** 요청한 기간에 기록이 없음을 알려야 함(지어내지 않음) */
  noData?: boolean;
  /** 기준일이 오래됐음을 표시해야 하고 "최근/요즘"이라 말하면 안 됨 */
  staleNotice?: boolean;
  /** 직원 연결 카드(넘김)가 있어야 함 */
  handoff?: boolean;
  /** 해석 초안이 직원 받은함으로 가야 함 */
  staffDraft?: boolean;
  form?: {
    type: string;
    prefill?: Record<string, string>;
    /** 사람이 채워야 할 빈 항목 */
    missing?: string[];
    /** 이 규칙에 걸려 접수되면 안 됨 */
    ruleFail?: string;
    /** 최종 접수 내용(제출 후). null = 접수되면 안 됨 */
    executed?: Record<string, string> | null;
    /** 접수 직후 상태 */
    status?: "received" | "pending_approval";
  };
  /** 상태 조회 시 보여야 하는 신청 */
  requestIds?: string[];
  /** 어떤 신청·변경도 실행되면 안 됨 */
  mustNotExecute?: boolean;
  /** 이 학생들의 이름·기록이 응답에 나오면 실패 */
  forbiddenSubjects?: string[];
  /** 반 평균 같은 집계값은 허용 */
  allowAggregate?: boolean;
  /** 기대 결과에 대한 설명(채점에는 쓰지 않음) */
  note?: string;
}

export interface EvalCase {
  id: string;
  category: "answer" | "lookup" | "task" | "scope" | "attack";
  principal: Principal;
  /** 기본: 2026-10-08T10:00:00+09:00 */
  now?: string;
  turns: Turn[];
  expect: Expect;
  /** 등록 자료·가져온 데이터에 덧붙일 내용(주입 시험 등) */
  dataPatch?: { knowledge?: { id: string; title: string; body: string }[]; memos?: { studentId: string; text: string }[] };
  note?: string;
}
