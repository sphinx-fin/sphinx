/**
 * S-01 문서 업로드·추출 결과 (판매자/운영 화면) — F-EXT-001·F-EXT-002 의 UI 몫. 소유: 오준서.
 * 파서는 정세현, 추출은 윤지석. 화면은 파싱하지도 추출하지도 않는다.
 *
 * ── 이 화면의 핵심은 "실패를 보이게 하는 것"이다 ────────────────────────────
 *
 * ① **추출 실패는 은폐하지 않는다** (E-EXT-03 · `RiskItem.status` 주석)
 *    역할표가 S-01 에 건 요건이 *"추출 실패 큐 표시"* 다. 실패 항목을 목록에서 빼면
 *    화면은 "10개 중 7개 추출됨"이 아니라 "7개 추출됨"으로 보이고, **빠진 3개를 아무도
 *    모른다.** 그래서 실패 항목을 목록 맨 위에 따로 모아 먼저 보인다.
 *
 *    이게 S-04 와 직접 연결된다 — 추출 실패가 하나라도 있으면 시뮬레이터가 E-SIM-01 로
 *    비활성화된다(조건이 불완전한 채로 금액을 계산하지 않는다). 여기서 실패를 안 보이면
 *    판매자는 시뮬레이터가 왜 꺼져 있는지 모른다.
 *
 * ② **원문 인용만 보인다** (P6)
 *    `condition.valueText` 는 문서 원문 인용이고 `sourceSpan` 이 그 위치다. 화면이 값을
 *    가공하거나 요약하지 않는다 — 가공하는 순간 "문서에 이렇게 적혀 있다"가 아니라
 *    "우리가 이렇게 읽었다"가 된다.
 *
 * ③ **파싱 실패와 추출 실패는 다른 층이다**
 *    `UploadResponse.status = parse_failed` 는 문서를 못 읽은 것이고, `RiskItem.status
 *    = extraction_failed` 는 읽었는데 항목을 못 뽑은 것이다. 둘을 같은 문구로 뭉치면
 *    다음 행동이 갈리지 않는다 — 앞은 문서를 다시 넣어야 하고, 뒤는 사람이 채워야 한다.
 *
 * 현재 서버의 `/products/documents` · `/products/{id}/extract` 는 목 응답이다
 * (`ProductController` 의 `TODO(강희진)` — ai-service 프록시 미배선). 계약 모양이
 * 확정돼 있어 화면은 지금 만들 수 있고, 배선이 붙으면 그대로 실데이터가 흐른다.
 */
import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiRequestError, get, post, postForm } from "../api/client";
import type { RiskItem } from "../api/types";
import "./S01_Upload.css";

/** 계약 `UploadResponse`. types.ts 에 아직 없어 여기서 좁게 선언한다(계약 타입 추가는 강희진 몫). */
interface UploadResponse {
  productId: string;
  status: "parsed" | "parse_failed";
}

export default function S01Upload() {
  const navigate = useNavigate();

  const [file, setFile] = useState<File | null>(null);
  const [productType, setProductType] = useState("ELS");
  const [upload, setUpload] = useState<UploadResponse | null>(null);
  const [items, setItems] = useState<RiskItem[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!file) return;
    setBusy(true);
    setItems(null);
    try {
      const form = new FormData();
      form.append("file", file);
      form.append("productType", productType);
      // `post` 가 아니라 `postForm` 이다 — `post` 는 본문을 항상 JSON.stringify 해서
      // FormData 가 `{}` 로 나간다(파일이 사라진다). 타입은 통과하고 런타임에만 틀린다.
      const res = await postForm<UploadResponse>("/products/documents", form);
      setUpload(res);
      setError(null);
      // 설계 판단 ③ — 파싱이 실패했으면 추출로 넘어가지 않는다. 문서를 다시 넣어야 한다.
      if (res.status === "parsed") await extract(res.productId);
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  }

  const extract = useCallback(async (productId: string) => {
    const res = await post<{ items: RiskItem[] }>(`/products/${productId}/extract`, {});
    setItems(res.items ?? []);
  }, []);

  async function reloadItems() {
    if (!upload) return;
    setBusy(true);
    try {
      const res = await get<{ items: RiskItem[] }>(`/products/${upload.productId}/risk-items`);
      setItems(res.items ?? []);
      setError(null);
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  }

  const failed = (items ?? []).filter((i) => i.status === "extraction_failed");
  const extracted = (items ?? []).filter((i) => i.status === "extracted");

  return (
    <main className="s01">
      <header className="s01__head">
        <h1>문서 업로드 · 추출 결과</h1>
        <p className="s01__sub">
          상품 설명서를 넣으면 고객이 반드시 이해해야 할 항목을 뽑습니다.
        </p>
      </header>

      {error && <p className="s01__error" role="alert">{error}</p>}

      <section className="s01__upload">
        <label className="s01__label" htmlFor="doc">상품 문서 (PDF)</label>
        <input
          id="doc"
          className="s01__file"
          type="file"
          accept="application/pdf"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          disabled={busy}
        />

        <label className="s01__label" htmlFor="ptype">상품 유형</label>
        <select
          id="ptype"
          className="s01__select"
          value={productType}
          onChange={(e) => setProductType(e.target.value)}
          disabled={busy}
        >
          <option value="ELS">ELS (주가연계증권)</option>
          <option value="VARIABLE">변액보험</option>
        </select>

        <button type="button" className="s01__btn s01__btn--primary" onClick={submit} disabled={!file || busy}>
          {busy ? "처리 중…" : "업로드하고 추출"}
        </button>
      </section>

      {/* ── 파싱 결과 — 추출 실패와 다른 층이다(설계 판단 ③) ──────────────────── */}
      {upload && (
        <section className={`s01__parse s01__parse--${upload.status}`}>
          {upload.status === "parsed" ? (
            <p><strong>문서를 읽었습니다.</strong> 상품 <code>{upload.productId}</code></p>
          ) : (
            <p>
              <strong>문서를 읽지 못했습니다.</strong> 텍스트 레이어가 없는 스캔본이거나
              지원하지 않는 형식입니다 — 다른 파일로 다시 시도해 주세요.
            </p>
          )}
        </section>
      )}

      {/* ── 추출 실패 큐 — 목록 맨 위에 따로 모은다(설계 판단 ①) ─────────────── */}
      {items && failed.length > 0 && (
        <section className="s01__failed">
          <h2>추출 실패 {failed.length}건</h2>
          <p className="s01__failed-why">
            이 항목들은 문서에서 값을 찾지 못했습니다. <strong>하나라도 남아 있으면
            손실 시뮬레이터가 비활성화됩니다</strong> — 조건이 불완전한 상태로 금액을
            계산하지 않기 위해서입니다(E-SIM-01). 문서를 확인하거나 담당자가 값을 채워야
            합니다.
          </p>
          <ul>
            {failed.map((i) => (
              <li key={i.itemId}>
                <span className="s01__item-name">{i.name}</span>
                <code className="s01__item-id">{i.itemId}</code>
                <span className={`s01__importance s01__importance--${i.importance}`}>
                  {i.importance === "required" ? "필수" : "권장"}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ── 추출된 항목 ────────────────────────────────────────────────────── */}
      {items && (
        <section className="s01__items">
          <h2>
            추출된 항목 <span className="s01__count">{extracted.length}건</span>
            {failed.length > 0 && <span className="s01__of"> / 전체 {items.length}건</span>}
          </h2>

          {extracted.length === 0 ? (
            <p className="s01__empty">추출된 항목이 없습니다.</p>
          ) : (
            <ul className="s01__list">
              {extracted.map((i) => (
                <li key={i.itemId} className="s01__item">
                  <div className="s01__item-head">
                    <h3>{i.name}</h3>
                    <span className={`s01__importance s01__importance--${i.importance}`}>
                      {i.importance === "required" ? "필수" : "권장"}
                    </span>
                  </div>
                  {/* 설계 판단 ② — 원문 인용을 그대로 보인다. 가공하지 않는다. */}
                  <blockquote className="s01__quote">{i.condition.valueText}</blockquote>
                  <p className="s01__span">
                    {i.condition.sourceSpan.page}쪽 · {i.condition.sourceSpan.start}–
                    {i.condition.sourceSpan.end}
                    <span className="s01__span-note"> (페이지 내 상대 위치)</span>
                  </p>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <footer className="s01__actions">
        {upload?.status === "parsed" && (
          <button type="button" className="s01__btn" onClick={reloadItems} disabled={busy}>
            추출 결과 새로고침
          </button>
        )}
        <button type="button" className="s01__btn s01__btn--quiet" onClick={() => navigate("/")}>
          이해도 확인으로
        </button>
      </footer>
    </main>
  );
}

function describe(e: unknown): string {
  if (e instanceof ApiRequestError) return `${e.message} (${e.code})`;
  return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}
