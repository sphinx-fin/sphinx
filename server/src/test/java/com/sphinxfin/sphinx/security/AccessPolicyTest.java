package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.security.AccessPolicy.Actor;
import com.sphinxfin.sphinx.security.AccessPolicy.Scope;
import com.sphinxfin.sphinx.security.AccessPolicy.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 접근 정책 평가. 소유: 정세현
 *
 * <p>여기서 지키는 것은 <b>기획서 7-4 역이용 방지의 두 층</b>이다. 역할 부재(ADR-001)만으로는
 * 부족하고 범위 분리가 함께 있어야 한다 — 그래서 "SELLER 에게 집계 그랜트가 없다"와
 * "MGR 은 자기 지점만 본다"를 각각 고정한다.
 *
 * <p>정책 파일이 유일한 근거이므로 <b>기대값을 여기 복제하지 않는다.</b> 실제
 * {@code rbac_policy.yaml} 을 읽어 평가하고, 그 결과가 기획서가 요구하는 모양인지를 본다.
 */
@DisplayName("AccessPolicy — 역할 + 범위")
class AccessPolicyTest {

    private final AccessPolicy policy = new AccessPolicy(new RbacPolicyFile());

    private static Actor seller(String id) {
        return new Actor(id, Role.SELLER, "BR-1");
    }

    private static Actor mgr(String branchId) {
        return new Actor("mgr-01", Role.MGR, branchId);
    }

    private static Target session(String ownerId, String branchId) {
        return Target.session("S-1", ownerId, branchId);
    }

    @Nested
    @DisplayName("ADR-001 시연 — SELLER 로 집계에 닿지 않는다")
    class AggregateIsClosedToSeller {

        @Test
        @DisplayName("SELLER + 집계 → 거부. 역할에 그랜트가 아예 없다")
        void sellerCannotReachAggregate() {
            AccessPolicy.Decision decision =
                    policy.decide(seller("seller-01"), "aggregate:heatmap:read", Target.aggregate());

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .as("권한을 안 주는 것과 줄 수 있는 대상이 없는 것은 다르다 — 여기는 후자다")
                    .contains("SELLER", "그랜트가 없다");
        }

        @Test
        @DisplayName("SELLER 가 own_session 그랜트를 가진 action 으로도 집계에 못 닿는다")
        void ownSessionGrantDoesNotReachAggregate() {
            AccessPolicy.Decision decision =
                    policy.decide(seller("seller-01"), "report:read", Target.aggregate());

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("own_session");
        }

        @Test
        @DisplayName("COMPL 은 org 범위로 집계를 본다")
        void complSeesOrgWideAggregate() {
            Actor compl = new Actor("compl-01", Role.COMPL, null);

            assertThat(policy.permits(compl, "aggregate:heatmap:read", Target.aggregate())).isTrue();
            assertThat(policy.grantedScope(compl, "aggregate:heatmap:read", Target.aggregate()))
                    .contains(Scope.ORG);
        }

        @Test
        @DisplayName("MGR 은 집계를 보되 범위가 branch 다 — 질의를 좁히는 값이 나온다")
        void mgrSeesBranchScopedAggregate() {
            assertThat(policy.grantedScope(mgr("BR-1"), "aggregate:heatmap:read", Target.aggregate()))
                    .as("불리언만 주면 MGR 에게 org 전체를 주게 된다 — 통과시킨 의미가 없어진다")
                    .contains(Scope.BRANCH);
        }
    }

    @Nested
    @DisplayName("own_session — 자기 세션만")
    class OwnSession {

        @Test
        @DisplayName("자기 세션은 허용")
        void ownSessionAllowed() {
            assertThat(policy.permits(seller("seller-01"), "report:read",
                    session("seller-01", "BR-1"))).isTrue();
        }

        @Test
        @DisplayName("남의 세션은 거부")
        void otherSessionDenied() {
            AccessPolicy.Decision decision = policy.decide(seller("seller-01"), "report:read",
                    session("seller-02", "BR-1"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("자기 세션이 아니다");
        }

        @Test
        @DisplayName("세션의 진행 주체를 모르면 거부 — 통과가 아니라 판단 불가다")
        void unknownOwnerDenied() {
            AccessPolicy.Decision decision = policy.decide(seller("seller-01"), "report:read",
                    session(null, "BR-1"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .as("Session 에 진행 주체가 아직 없다(AccessGuard TODO) — 그 상태를 통과로 두면 안 된다")
                    .contains("판단할 수 없다");
        }
    }

    @Nested
    @DisplayName("branch — 자기 지점만")
    class Branch {

        @Test
        @DisplayName("같은 지점 세션은 허용")
        void sameBranchAllowed() {
            assertThat(policy.permits(mgr("BR-1"), "report:read", session("seller-01", "BR-1"))).isTrue();
        }

        @Test
        @DisplayName("다른 지점 세션은 거부")
        void otherBranchDenied() {
            AccessPolicy.Decision decision =
                    policy.decide(mgr("BR-1"), "report:read", session("seller-09", "BR-9"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("다른 지점");
        }

        @Test
        @DisplayName("❗행위자의 지점을 모르면 거부 — 계정에 지점이 안 실린 상태다 (10.5)")
        void unknownActorBranchDenied() {
            AccessPolicy.Decision decision =
                    policy.decide(mgr(null), "report:read", session("seller-01", "BR-1"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .as("통과시키면 '지점으로 제한했다' 가 거짓이 된다. 대신 MGR 기능이 전부 403 이 "
                            + "되므로 사유로 구별한다")
                    .contains("소속 지점을 알 수 없어", "10.5");
        }
    }

    @Nested
    @DisplayName("집계는 종류로 가른다 — 값의 모양으로 추론하지 않는다")
    class AggregateIsExplicit {

        @Test
        @DisplayName("❗조회 실패한 세션이 집계로 오인되지 않는다 — MGR 이 남의 지점 ID 를 찔러도 거부")
        void unresolvedSessionIsNotAggregate() {
            // AccessGuard.targetOf 는 없는 세션을 (null, null, null) 로 접는다.
            Target unresolved = new Target(null, null, null);

            AccessPolicy.Decision decision = policy.decide(mgr("BR-9"), "report:read", unresolved);

            assertThat(decision.allowed())
                    .as("전에는 이 값이 집계로 보여 branch 그랜트가 통과시켰다 — 실재하지 않는 세션이 "
                            + "실재하는 남의 지점 세션보다 더 허용되는 역전이었다 (PR #99 리뷰)")
                    .isFalse();
            assertThat(decision.reason()).contains("판단할 수 없다");
        }

        @Test
        @DisplayName("집계는 팩토리로만 만들어진다")
        void aggregateOnlyViaFactory() {
            assertThat(policy.permits(mgr("BR-1"), "aggregate:heatmap:read", Target.aggregate())).isTrue();
            assertThat(policy.permits(mgr("BR-1"), "aggregate:heatmap:read", new Target(null, null, null)))
                    .as("같은 필드 값이어도 세션 대상이면 집계 그랜트로 통과하지 않는다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("기본값은 거부다")
    class DenyByDefault {

        @Test
        @DisplayName("정책에 없는 action 은 거부")
        void unknownActionDenied() {
            AccessPolicy.Decision decision =
                    policy.decide(seller("seller-01"), "session:delete", session("seller-01", "BR-1"));

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).contains("정책에 없는 action");
        }

        @Test
        @DisplayName("행위자가 없으면 거부")
        void noActorDenied() {
            assertThat(policy.permits(null, "report:read", session("x", "BR-1"))).isFalse();
        }

        @Test
        @DisplayName("전문과 요약은 같은 문서가 아니다 — CUST 에게 전문 그랜트가 없다")
        void summaryGrantDoesNotOpenTheFullReport() {
            // 그랜트 유무만 본다. 이 두 action 이 CUST 에게 **도달 가능한가**는 다른 문제이고
            // (도달 불가다 — 아래 CustIsUnreachable), 여기서 보는 것은 "요약 그랜트가 전문까지
            // 열어 주지 않는다" 하나다. 그래서 ownerId 가 맞는 Target 을 일부러 쓴다.
            Actor cust = new Actor("cust-01", Role.CUST, null);
            Target ownedByCust = session("cust-01", "BR-1");

            assertThat(policy.permits(cust, "report:summary:read", ownedByCust)).isTrue();
            assertThat(policy.permits(cust, "report:read", ownedByCust))
                    .as("판매자용 전문과 고객 교부용 요약은 같은 문서가 아니다")
                    .isFalse();
        }

        @Test
        @DisplayName("MGR 은 승인하고 SELLER 는 못 한다 — 요청자 ≠ 승인자 (ADR-002)")
        void approverIsNotRequester() {
            assertThat(policy.permits(mgr("BR-1"), "override:approve", session("seller-01", "BR-1"))).isTrue();
            assertThat(policy.permits(seller("seller-01"), "override:approve", session("seller-01", "BR-1")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("정책 파일이 유일한 근거다")
    class PolicyFileIsTheSource {

        @Test
        @DisplayName("grantedScope 는 허용될 때만 값을 낸다")
        void grantedScopeEmptyWhenDenied() {
            assertThat(policy.grantedScope(seller("seller-01"), "aggregate:heatmap:read", Target.aggregate()))
                    .isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("report:issue 는 SELLER 만 — MGR·COMPL 은 조회만 한다 (PR #95)")
        void issueIsSellerOnly() {
            Target own = session("seller-01", "BR-1");

            assertThat(policy.permits(seller("seller-01"), "report:issue", own)).isTrue();
            assertThat(policy.permits(mgr("BR-1"), "report:issue", own))
                    .as("교부는 그 세션을 진행한 창구 직원이 한다")
                    .isFalse();
            assertThat(policy.permits(new Actor("compl-01", Role.COMPL, null), "report:issue", own))
                    .as("조회와 같은 action 이면 COMPL 이 org 전체에 대해 발행할 수 있었다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("읽기와 진행을 가른다 — 승인자는 읽되 세션을 몰지 못한다 (이슈 #124)")
    class ReadIsNotInterview {

        @Test
        @DisplayName("MGR 은 지점 내 세션을 읽는다 — 사유를 모르고 승인하는 상태를 없앤다")
        void mgrCanReadBranchSession() {
            assertThat(policy.permits(mgr("BR-1"), "session:read", session("seller-01", "BR-1")))
                    .as("승인자가 승인 대상을 못 읽으면 오버라이드 사유가 응답에 실려도 닿지 않는다")
                    .isTrue();
        }

        @Test
        @DisplayName("❗MGR 은 같은 세션을 진행하지는 못한다 — 여기가 가른 이유다")
        void mgrCannotDriveTheSession() {
            Target inBranch = session("seller-01", "BR-1");

            assertThat(policy.permits(mgr("BR-1"), "session:interview", inBranch))
                    .as("session:interview 는 questions/next · re-explain · abort 를 덮는다. "
                        + "읽기를 열려고 여기에 MGR 을 붙이면 지점 내 적색 세션을 중단하거나 "
                        + "질문을 다시 돌릴 권한까지 열린다 — 운영 압박이 들어왔을 때 문제가 "
                        + "되는 권한이다(기획 7-4 · ADR-001)")
                    .isFalse();
        }

        @Test
        @DisplayName("COMPL 은 전체를 읽되 역시 진행하지 못한다")
        void complReadsButDoesNotDrive() {
            Actor compl = new Actor("compl-01", Role.COMPL, null);
            Target any = session("seller-01", "BR-9");

            assertThat(policy.permits(compl, "session:read", any)).isTrue();
            assertThat(policy.permits(compl, "session:interview", any)).isFalse();
        }

        @Test
        @DisplayName("면담을 진행하는 SELLER 는 둘 다 가진다 — 가르면서 기존 경로를 끊지 않았다")
        void sellerKeepsBoth() {
            Target own = session("seller-01", "BR-1");

            assertThat(policy.permits(seller("seller-01"), "session:read", own)).isTrue();
            assertThat(policy.permits(seller("seller-01"), "session:interview", own)).isTrue();
        }

        @Test
        @DisplayName("읽기도 범위를 지킨다 — 남의 지점은 MGR 도 못 읽는다")
        void readStillRespectsScope() {
            assertThat(policy.permits(mgr("BR-1"), "session:read", session("seller-02", "BR-2")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("상품 카탈로그 — 판매 라인은 읽기만 한다 (이슈 #69, 결정 10.36)")
    class ProductCatalogIsReadOnlyForSales {

        /**
         * 상품 카탈로그는 세션도 집계도 아니다. 두 action 모두 {@code scope: org} 라 대상의
         * 종류가 판단을 바꾸지 않는데, 그 사실 자체를 여기서 고정한다 — 나중에 범위를 좁히면
         * 이 테스트가 먼저 깨진다.
         */
        private static final Target CATALOG = Target.aggregate();

        @Test
        @DisplayName("❗SELLER 는 위험항목을 만들 수 없다 — 항목이 곧 게이트가 물을 질문이다")
        void sellerCannotExtract() {
            AccessPolicy.Decision decision = policy.decide(seller("seller-01"), "product:manage", CATALOG);

            assertThat(decision.allowed())
                    .as("판매 라인이 자기가 답해야 할 질문의 목록을 편집할 수 있으면 게이트가 "
                            + "조용히 느슨해진다 — 기획 7-4 가 막으려는 경로 중 가장 짧다")
                    .isFalse();
            assertThat(decision.reason())
                    .as("범위가 어긋난 것이 아니라 역할에 그랜트가 아예 없어야 한다 (ADR-001 과 같은 결)")
                    .contains("SELLER", "그랜트가 없다");
        }

        @Test
        @DisplayName("❗MGR 도 못 만든다 — 지점장이라는 이유로 분모를 바꿀 수 있으면 안 된다")
        void mgrCannotExtract() {
            assertThat(policy.permits(mgr("BR-1"), "product:manage", CATALOG))
                    .as("운영 압박이 들어오는 자리가 지점이다")
                    .isFalse();
        }

        @Test
        @DisplayName("COMPL 도 못 만든다 — 점검하는 쪽과 만드는 쪽을 겹치지 않는다")
        void complCannotExtract() {
            assertThat(policy.permits(new Actor("compl-01", Role.COMPL, null), "product:manage", CATALOG))
                    .isFalse();
        }

        @Test
        @DisplayName("등록·추출은 ADMIN 뿐이다")
        void adminManages() {
            assertThat(policy.permits(new Actor("admin-01", Role.ADMIN, null), "product:manage", CATALOG))
                    .isTrue();
        }

        @Test
        @DisplayName("읽기는 판매·감독 전원에게 열린다 — S-02 가 상품을 고를 수 있어야 한다")
        void everyoneWhoRunsSessionsCanRead() {
            assertThat(policy.permits(seller("seller-01"), "product:read", CATALOG)).isTrue();
            assertThat(policy.permits(mgr("BR-1"), "product:read", CATALOG)).isTrue();
            assertThat(policy.permits(new Actor("compl-01", Role.COMPL, null), "product:read", CATALOG)).isTrue();
        }

        @Test
        @DisplayName("❗CUST 는 카탈로그에 닿지 않는다 — 고객 화면은 세션을 통해서만 본다")
        void custIsNotInTheCatalog() {
            Actor cust = new Actor("cust-01", Role.CUST, null);

            assertThat(policy.permits(cust, "product:read", CATALOG)).isFalse();
            assertThat(policy.permits(cust, "product:manage", CATALOG)).isFalse();
        }

        @Test
        @DisplayName("읽기와 관리가 같은 action 이 아니다 — 하나로 합치면 SELLER 가 추출까지 얻는다")
        void readAndManageAreDistinct() {
            Actor admin = new Actor("admin-01", Role.ADMIN, null);

            assertThat(policy.permits(seller("seller-01"), "product:read", CATALOG)).isTrue();
            assertThat(policy.permits(seller("seller-01"), "product:manage", CATALOG)).isFalse();
            assertThat(policy.permits(admin, "product:read", CATALOG)).isTrue();
            assertThat(policy.permits(admin, "product:manage", CATALOG)).isTrue();
        }
    }

    @Nested
    @DisplayName("❗CUST — session:answer 는 역할로 막고, 남은 그랜트는 도달 불가다 (이슈 #166)")
    class CustIsUnreachable {

        /**
         * {@code AccessGuard.targetOf} 가 <b>실제로 만드는</b> 모양이다 — {@code ownerId} 에는
         * 언제나 {@code Session.sellerId} 가 들어간다. 세션에 고객을 가리키는 필드가 없다.
         *
         * <p>기존 CUST 테스트가 {@code session("cust-01", ...)} 로 <b>고객이 소유자인 Target</b>
         * 을 손으로 만들고 있었다. 그런 Target 은 배선에서 만들어지지 않으므로, 그 단정은
         * <b>일어날 수 없는 일을 참이라고 말하고</b> 있었다.
         */
        private static final Target AS_WIRED = Target.session("S-1", "seller-01", "BR-1");

        private static final Actor CUST = new Actor("cust-01", Role.CUST, null);

        @Test
        @DisplayName("❗session:answer 에는 CUST 그랜트가 없다 — B안이 들어간 자리다")
        void theGrantIsGone() {
            assertThat(new RbacPolicyFile().grants("session:answer"))
                    .as("이 단정이 깨지면 누가 CUST 를 되돌린 것이다 — #166 은 B 로 결정됐고, "
                            + "되돌리려면 그 결정과 rbac_policy.yaml 의 근거 두 개를 먼저 봐야 한다")
                    .noneMatch(g -> g.roles().contains(Role.CUST));
        }

        @Test
        @DisplayName("❗이제 **역할이** 막는다 — 사유도 참이다")
        void theRoleIsWhatBlocksNow() {
            AccessPolicy.Decision decision = policy.decide(CUST, "session:answer", AS_WIRED);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .as("B 이전에는 '자기 세션이 아니다' 였는데 그건 거짓이었다 — 자기 세션이 "
                            + "아니어서가 아니라 대조할 고객 식별자가 세션에 없어서다. "
                            + "그랜트를 걷으니 AccessPolicy 를 안 고치고 사유가 참이 됐다")
                    .contains("그랜트가 없다")
                    .doesNotContain("자기 세션이 아니다");
        }

        @Test
        @DisplayName("❗이름이 sellerId 와 같아도 못 한다 — B 이전에는 통과했다")
        void aCustNamedLikeTheSellerIsBlockedToo() {
            // ❗이것이 B 를 고른 근거다(#166 ❶). 이전에는 역할이 그랜트 유무만 정하고 그 뒤로는
            // 이름 비교뿐이라, actorId 가 sellerId 와 같으면 **CUST 가 판매자와 똑같이 통과했다.**
            // 즉 이 action 에서 역할은 구속이 아니었다. 지금은 그랜트 검사에서 끝난다.
            Actor custNamedLikeTheSeller = new Actor("seller-01", Role.CUST, null);

            assertThat(policy.permits(custNamedLikeTheSeller, "session:answer", AS_WIRED))
                    .as("여기가 다시 true 가 되면 역할이 또 무의미해진 것이다")
                    .isFalse();
        }

        @Test
        @DisplayName("❗도달 불가라는 사실은 report:summary:read 에 살아 있다 — 그랜트가 남았다")
        void theUnreachableGrantSurvivesOnTheSummary() {
            // B 는 session:answer 만 걷었다. 고객 교부용 요약은 기획 산출물이라 F-GTE-004
            // 판단으로 뗐고(#166 조건 ②), 그래서 **CUST 그랜트가 있는데 도달 못 하는** 상태는
            // 그쪽에 그대로 남는다. #166 이 세운 사실을 붙들 자리가 여기다.
            assertThat(new RbacPolicyFile().grants("report:summary:read"))
                    .as("이 그랜트까지 걷으면 CUST 도달 불가를 보여줄 자리가 없어진다")
                    .anyMatch(g -> g.roles().contains(Role.CUST));

            AccessPolicy.Decision decision = policy.decide(CUST, "report:summary:read", AS_WIRED);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .as("그랜트는 있으므로 범위 판정까지 내려간다 — 거기서 이름이 안 맞아 막힌다")
                    .contains("자기 세션이 아니다");
        }

        @Test
        @DisplayName("판매자 경로는 멀쩡하다 — 오늘 대면 채널의 행위자는 SELLER 다")
        void theSellerPathStillWorks() {
            // 도달 불가가 기능을 막고 있지는 않다. S-02 에서 세션을 만든 판매자가 같은
            // 브라우저로 S-03 을 열어 단말을 넘겨주는 구조라, 인증 주체는 SELLER 하나다.
            assertThat(policy.permits(seller("seller-01"), "session:answer", AS_WIRED)).isTrue();
        }
    }

    @Nested
    @DisplayName("손실 시뮬레이터 — 진행 행위지 감독 대상이 아니다 (이슈 #214)")
    class SimulatorIsInterviewNotOversight {

        private final RbacPolicyFile file = new RbacPolicyFile();

        /** seller-01 이 BR-1 에서 진행 중인 세션. S-04 는 이 위에서 돈다. */
        private static final Target OWN = Target.session("S-1", "seller-01", "BR-1");

        @Test
        @DisplayName("면담을 진행하는 SELLER 가 자기 세션에서 돌린다 — 데모 경로")
        void theSellerRunsItOnTheirOwnSession() {
            assertThat(policy.permits(seller("seller-01"), "session:simulate", OWN)).isTrue();
        }

        @Test
        @DisplayName("남의 세션에서는 못 돌린다 — own_session 이 그대로 선다")
        void notOnSomeoneElsesSession() {
            assertThat(policy.permits(seller("seller-02"), "session:simulate", OWN)).isFalse();
        }

        /**
         * ❗<b>이 두 단정이 #214 에서 내린 판단이다.</b> MGR·COMPL 은 같은 세션을 <i>읽는다</i>.
         * 그런데 시뮬레이션은 못 돌린다 — 그 대비가 여기 나란히 있어야 판단이 보인다.
         *
         * <p>읽기에 감독 역할이 붙는 이유는 그 세션에서 무슨 일이 있었는지를 봐야 해서다.
         * 시뮬레이션 결과는 세션 기록의 일부가 아니라 (금액, 상품 조건, 지수 스냅샷) 의 순수
         * 함수라(P2), 남의 세션에 대해 돌려도 그 세션에 관해 알게 되는 것이 없다. 감독에
         * 필요한 것은 {@code report:read} 가 이미 준다.
         *
         * <p>넓히려면 이 테스트를 고쳐야 하고 그 PR 이 기록에 남는다. 그게 요지다.
         */
        @Test
        @DisplayName("❗MGR 은 지점 내 세션을 읽되 시뮬레이터는 못 돌린다")
        void theBranchManagerReadsButDoesNotRun() {
            assertThat(policy.permits(mgr("BR-1"), "session:read", OWN))
                    .as("읽기는 열려 있다 — 여기가 닫히면 이 대비가 아무것도 안 잰다")
                    .isTrue();
            assertThat(policy.permits(mgr("BR-1"), "session:simulate", OWN))
                    .as("고객 앞 화면을 띄우는 것은 면담 진행이다 (session:interview 와 같은 결)")
                    .isFalse();
        }

        @Test
        @DisplayName("❗COMPL 도 같다 — 전체를 읽되 진행하지는 않는다")
        void complianceReadsButDoesNotRun() {
            Actor compl = new Actor("compl-01", Role.COMPL, null);

            assertThat(policy.permits(compl, "session:read", OWN)).isTrue();
            assertThat(policy.permits(compl, "session:simulate", OWN)).isFalse();
        }

        /**
         * ❗<b>이 이슈의 요지가 이 한 줄이다.</b> {@code #76} · 기획 7-2 가 요구하는 것은
         * <i>"누가 무엇을 봤는지 남는다"</i> 이고, S-04 는 데모 경로에 직접 있는 화면이다.
         * 비audited 인 {@code session:interview} 를 재사용했다면 이 요건이 안 채워진다.
         */
        @Test
        @DisplayName("❗감사 대상이다 — S-04 를 띄운 사실이 불변 기록에 남는다")
        void showingTheScreenIsAudited() {
            assertThat(file.audited())
                    .as("여기서 빠지면 AuditInterceptor 가 이 호출을 조용히 넘긴다 — "
                            + "로그 0건이 '아무도 안 봤다' 로 읽힌다")
                    .contains("session:simulate");
        }
    }
}
