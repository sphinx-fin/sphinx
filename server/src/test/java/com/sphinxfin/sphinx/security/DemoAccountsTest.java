package com.sphinxfin.sphinx.security;

import com.sphinxfin.sphinx.security.AccessPolicy.Target;
import com.sphinxfin.sphinx.security.DemoAccountsFile.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할별 계정 명부. 소유: 정세현 (결정 10.5)
 *
 * <p><b>"파일이 파싱된다" 를 확인하는 테스트가 아니다.</b> 이 명부의 존재 이유는 9/3 리허설에서
 * 기획 7-4 를 <b>실물로 보여주는 것</b>이고, 그러려면 특정한 계정 <b>쌍</b>이 있어야 한다 —
 * 예컨대 SELLER 가 하나뿐이면 "남의 세션은 못 읽는다" 를 보여줄 대상이 없다.
 *
 * <p>그래서 시연 넷을 <b>실제 {@link AccessPolicy} 로 평가해서</b> 고정한다. 명부에서 계정을
 * 지우면 여기가 먼저 깨진다.
 *
 * <p>기대값을 여기 복제하지 않는다 — 실제 {@code rbac_policy.yaml} 과 실제
 * {@code demo_accounts.yaml} 을 읽어 판단하고, 그 결과가 기획서가 요구하는 모양인지만 본다.
 */
@DisplayName("역할별 계정 명부 — 시연이 성립하는가 (결정 10.5)")
class DemoAccountsTest {

    private final DemoAccountsFile roster = new DemoAccountsFile();
    private final AccessPolicy policy = new AccessPolicy(new RbacPolicyFile());

    private Account account(String id) {
        return roster.byId(id).orElseThrow(() ->
                new AssertionError("명부에서 " + id + " 이 사라졌다 — 이 계정에 걸린 시연이 있다"));
    }

    /** 그 계정이 진행한 세션. 귀속은 계정에서만 온다(요청에서 받지 않는다). */
    private static Target sessionOf(Account seller) {
        return Target.session("S-1", seller.actorId(), seller.branchId());
    }

    @Nested
    @DisplayName("시연 넷 — 계정이 하나였을 때는 전부 '계정이 하나라서' 로 뭉개졌다")
    class TheFourDemonstrations {

        @Test
        @DisplayName("❗1. SELLER 로 집계에 닿지 않는다 (ADR-001)")
        void sellerCannotReachAggregate() {
            AccessPolicy.Decision decision = policy.decide(
                    account("seller-01").toActor(), "aggregate:heatmap:read", Target.aggregate());

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason())
                    .as("권한을 안 주는 것과 줄 수 있는 대상이 없는 것은 다르다 — 후자여야 한다")
                    .contains("SELLER", "그랜트가 없다");
        }

        @Test
        @DisplayName("❗2. 남의 세션은 못 읽는다 — 같은 지점이어도 그렇다")
        void otherSellersSessionIsDenied() {
            Account owner = account("seller-01");
            Account other = account("seller-02");

            assertThat(other.branchId())
                    .as("같은 지점이어야 이 시연이 own_session 을 보는 것이 된다 — 지점이 다르면 "
                            + "branch 에서 막힌 것인지 own_session 에서 막힌 것인지 구별되지 않는다")
                    .isEqualTo(owner.branchId());

            assertThat(policy.permits(owner.toActor(), "session:read", sessionOf(owner))).isTrue();
            assertThat(policy.permits(other.toActor(), "session:read", sessionOf(owner))).isFalse();
        }

        @Test
        @DisplayName("❗3. MGR 은 자기 지점만 본다 — 범위 밖 세션을 만들 사람이 명부에 있어야 한다")
        void managerSeesOwnBranchOnly() {
            Account mgr = account("mgr-01");
            Account inBranch = account("seller-01");
            Account outOfBranch = account("seller-03");

            assertThat(inBranch.branchId()).isEqualTo(mgr.branchId());
            assertThat(outOfBranch.branchId())
                    .as("같은 지점만 있으면 branch 와 org 가 구별되지 않는다 — 좁히고 있다는 "
                            + "주장을 보일 수 없다")
                    .isNotEqualTo(mgr.branchId());

            assertThat(policy.permits(mgr.toActor(), "session:read", sessionOf(inBranch))).isTrue();
            assertThat(policy.permits(mgr.toActor(), "session:read", sessionOf(outOfBranch))).isFalse();
        }

        @Test
        @DisplayName("4. COMPL 은 전체를 본다 — 두 지점 모두")
        void complianceSeesEverything() {
            Account compl = account("compl-01");

            assertThat(policy.permits(compl.toActor(), "session:read", sessionOf(account("seller-01")))).isTrue();
            assertThat(policy.permits(compl.toActor(), "session:read", sessionOf(account("seller-03")))).isTrue();
            assertThat(policy.permits(compl.toActor(), "aggregate:heatmap:read", Target.aggregate())).isTrue();
        }
    }

    @Nested
    @DisplayName("명부가 갖춰야 하는 모양")
    class RosterShape {

        @Test
        @DisplayName("❗모든 역할에 계정이 하나씩은 있다 — 없는 역할은 시연도 감사도 안 된다")
        void everyRoleHasAnAccount() {
            Set<Role> covered = roster.accounts().stream()
                    .map(Account::role).collect(Collectors.toUnmodifiableSet());

            assertThat(covered)
                    .as("Role enum 에 있는데 명부에 없으면 그 역할로는 아무 요청도 못 만든다 — "
                            + "감사 로그에서 그 역할의 행이 영원히 0건이고, 그 0건이 "
                            + "'그 역할이 아무것도 안 했다' 로 읽힌다")
                    .containsExactlyInAnyOrder(Role.values());
        }

        @Test
        @DisplayName("❗지점을 쓰는 역할에만 지점이 있다 — org 역할에 지점을 주면 오해를 부른다")
        void onlyBranchScopedRolesCarryABranch() {
            for (Account a : roster.accounts()) {
                if (a.role() == Role.SELLER || a.role() == Role.MGR) {
                    assertThat(a.branchId())
                            .as("%s(%s) 에 지점이 없다 — scope: branch 판단이 '알 수 없다' 로 "
                                    + "거부되고, 그건 '막고 있다' 와 로그에서 같아 보인다", a.actorId(), a.role())
                            .isNotNull();
                } else {
                    assertThat(a.branchId())
                            .as("%s(%s) 는 org·own_session 만 쓴다. 지점을 주면 무의미하고, "
                                    + "다음 사람이 'branch 로 좁혀도 되겠다' 고 읽는다", a.actorId(), a.role())
                            .isNull();
                }
            }
        }

        @Test
        @DisplayName("계정 id 가 겹치지 않는다 — 겹치면 감사 로그의 행위자가 다시 뭉개진다")
        void actorIdsAreUnique() {
            List<String> ids = roster.accounts().stream().map(Account::actorId).toList();

            assertThat(ids).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("지점 코드가 실재한다 — 오타는 그 사람만 자기 지점 세션을 못 읽게 만든다")
        void branchCodesResolve() {
            for (Account a : roster.accounts()) {
                if (a.branchId() != null) {
                    assertThat(roster.branches()).containsKey(a.branchId());
                }
            }
        }

        @Test
        @DisplayName("❗명부에 비밀번호가 없다 — 있으면 레포가 곧 배포 자격증명이 된다")
        void rosterCarriesNoSecrets() throws Exception {
            String raw = new String(getClass().getClassLoader()
                    .getResourceAsStream("demo_accounts.yaml").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);

            assertThat(raw.toLowerCase())
                    .as("자격증명은 환경변수다. 파일에 넣으면 지워도 git 이력에 남는다")
                    .doesNotContain("password")
                    .doesNotContain("secret")
                    .doesNotContain("passwd");
        }
    }
}
