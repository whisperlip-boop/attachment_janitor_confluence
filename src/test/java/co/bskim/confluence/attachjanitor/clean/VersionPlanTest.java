package co.bskim.confluence.attachjanitor.clean;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 이 산술이 틀리면 살아 있는 파일의 내용이 조용히 바뀐다(실측 32번). 그래서 시험이 많다.
 */
public class VersionPlanTest
{
    private static List<Integer> versions(int... numbers)
    {
        List<Integer> list = new ArrayList<Integer>();
        for (int number : numbers)
        {
            list.add(Integer.valueOf(number));
        }
        return list;
    }

    @Test
    public void keepsTheRequestedNumberCountingTheCurrentVersion()
    {
        // 버전 8개 = 최신 1 + 구버전 7. keep=3 이면 최신 + 구버전 2개가 남는다.
        assertEquals(versions(1, 2, 3, 4, 5).toString(),
                VersionPlan.toRemove(versions(1, 2, 3, 4, 5, 6, 7), 8, 3).toString());
    }

    @Test
    public void keepOfOneRemovesEveryOldVersion()
    {
        assertEquals(versions(1, 2, 3).toString(),
                VersionPlan.toRemove(versions(1, 2, 3), 4, 1).toString());
    }

    @Test
    public void keepBeyondWhatExistsRemovesNothing()
    {
        assertEquals("[]", VersionPlan.toRemove(versions(1, 2, 3), 4, 4).toString());
        assertEquals("[]", VersionPlan.toRemove(versions(1, 2, 3), 4, 99).toString());
        assertEquals("[]", VersionPlan.toRemove(versions(), 1, 3).toString());
    }

    @Test
    public void removesOldestFirst()
    {
        // 도중에 죽어도 최근 이력이 남아야 한다. 목록 순서가 곧 삭제 순서다.
        assertEquals("[1, 2, 3, 4]",
                VersionPlan.toRemove(versions(1, 2, 3, 4, 5, 6), 7, 3).toString());
    }

    @Test
    public void doesNotTrustTheIncomingOrder()
    {
        // getPreviousVersions() 의 순서는 문서화돼 있지 않다. 뒤섞여 들어와도 같은 답.
        assertEquals("[1, 2, 3, 4]",
                VersionPlan.toRemove(versions(6, 3, 1, 5, 4, 2), 7, 3).toString());
    }

    @Test
    public void copesWithGapsInVersionNumbers()
    {
        // 이미 지운 버전이 있으면 번호가 비어 있다 — 재부여되지 않는다(실측 32번).
        assertEquals("[1, 2, 4]",
                VersionPlan.toRemove(versions(1, 2, 4, 6, 7), 8, 3).toString());
    }

    @Test
    public void keepBelowOneIsRaisedToOne()
    {
        // keep=0 은 "최신까지 지워라"가 되어 버린다. 그런 요청은 받지 않는다.
        assertEquals(VersionPlan.toRemove(versions(1, 2, 3), 4, 1).toString(),
                VersionPlan.toRemove(versions(1, 2, 3), 4, 0).toString());
        assertEquals(VersionPlan.toRemove(versions(1, 2, 3), 4, 1).toString(),
                VersionPlan.toRemove(versions(1, 2, 3), 4, -5).toString());
    }

    @Test
    public void neverReturnsTheHighestVersionWhenKeepIsAtLeastOne()
    {
        // 구버전만 넘어온다는 계약이지만, 계약이 깨져도 최신 번호를 돌려주면 안 된다.
        // keep >= 1 이면 가장 큰 번호는 항상 살아남는지 훑어 확인한다.
        // 계약을 일부러 깨뜨린다: 최신(10)이 후보 목록에 섞여 들어온 상황.
        List<Integer> all = versions(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        for (int keep = 1; keep <= 12; keep++)
        {
            List<Integer> remove = VersionPlan.toRemove(all, 10, keep);
            assertEquals("keep=" + keep + " 에서 최신 번호를 지우려 한다",
                    false, remove.contains(Integer.valueOf(10)));
        }
    }

    @Test
    public void nullsAndEmptyInputAreSurvivable()
    {
        assertEquals("[]", VersionPlan.toRemove(null, 5, 3).toString());
        assertEquals("[1]", VersionPlan.toRemove(Arrays.asList(null, 1, null, 2), 3, 2).toString());
    }
}
