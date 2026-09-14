package co.bskim.confluence.attachjanitor.clean;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 화면이 체크박스를 그릴지 정하는 규칙 —
 * <b>"지금 유지 개수로 지울 것이 있나"</b> — 를 서버 쪽 산술로 못 박는다.
 *
 * <p>화면의 {@code removableAt(row, keep)} 은 {@code oldVersionCount - (keep - 1)} 인데,
 * 그 값이 {@link VersionPlan} 이 실제로 지우는 개수와 어긋나면 <b>누를 수 있는데 아무
 * 일도 안 일어나는 단추</b>가 생긴다. 실제로 그 버그가 있었다: 최근 3개 유지로 정리한
 * 파일에 체크박스가 그대로 남아 있었고, 눌러도 미리보기가 비어 있었다.
 */
public class RemovableAtTest
{
    /** 화면이 쓰는 것과 같은 식. */
    private static int removableAt(int oldVersionCount, int keep)
    {
        return Math.max(0, oldVersionCount - (Math.max(1, keep) - 1));
    }

    /** 구버전 번호 {@code 1..count} 를 가진 첨부. 최신은 {@code count + 1} 이다. */
    private static int actuallyRemoved(int oldVersionCount, int keep)
    {
        List<Integer> versions = new ArrayList<Integer>();
        for (int number = 1; number <= oldVersionCount; number++)
        {
            versions.add(Integer.valueOf(number));
        }
        return VersionPlan.toRemove(versions, oldVersionCount + 1, keep).size();
    }

    @Test
    public void theScreenAndTheServerAgreeEverywhere()
    {
        for (int old = 0; old <= 30; old++)
        {
            for (int keep = 1; keep <= 32; keep++)
            {
                assertEquals("구버전 " + old + "개 · 유지 " + keep,
                        actuallyRemoved(old, keep), removableAt(old, keep));
            }
        }
    }

    @Test
    public void theCaseThatWasBroken()
    {
        // 최근 3개 유지로 이미 정리한 파일: 구버전 2개가 남는다. 더 지울 것이 없으므로
        // 체크박스가 없어야 하고, 실제로 지워지는 개수도 0 이다.
        assertEquals(0, removableAt(2, 3));
        assertEquals(0, actuallyRemoved(2, 3));
        // 유지 개수를 낮추면 그때는 지울 것이 생긴다.
        assertEquals(1, removableAt(2, 2));
        assertEquals(2, removableAt(2, 1));
    }
}
