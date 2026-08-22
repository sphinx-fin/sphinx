/** contracts/*.schema.json 과 1:1. 스키마 변경 시 이 파일도 갱신 (소유자: 강희진 승인). */
export type Grade = "U1" | "U2" | "U3" | "U4";
export type Signal = "GREEN" | "YELLOW" | "RED";

export interface Judgment {
  item_id: string;
  grade: Grade;
  confidence: number;
  evidence: { utterance_quote: string; rubric_clause: string };
  reason: string;
  misconception_type: string | null;
}

export interface InputMeta {
  first_keystroke_delay_ms: number;
  total_input_ms: number;
  paste_detected: boolean;
  backspace_count: number;
}
