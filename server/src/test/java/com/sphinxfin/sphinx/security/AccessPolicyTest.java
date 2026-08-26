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
        return new Target("S-1", ownerId, branchId);
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
        @DisplayName("CUST 는 요약만 본다 — 전문은 거부")
        void custSeesSummaryOnly() {
            Actor cust = new Actor("cust-01", Role.CUST, null);
            Target own = session("cust-01", "BR-1");

            assertThat(policy.permits(cust, "report:summary:read", own)).isTrue();
            assertThat(policy.permits(cust, "report:read", own))
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
}
