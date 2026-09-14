package co.bskim.confluence.attachjanitor.model;

/**
 * 첨부 하나가 여러 개 받을 수 있는 표시. 축은 <b>얼마나 큰가 / 얼마나 낭비인가</b> 다.
 *
 * <p>{@link Label} 과 직교한다. 활성이면서 구버전이 20개일 수 있고, 고아이면서 중복일
 * 수도 있다. 실무에서 관리자가 가장 먼저 하는 질문이 "구버전 많은 것만 모아 봐" 라서
 * 화면에서 배지만으로 필터할 수 있어야 한다.
 *
 * <p>임계값은 설정 화면에서 바꾼다({@code Settings}). 여기 열거형에는 조건의 <em>이름</em>
 * 만 있고 숫자는 없다.
 */
public enum Badge
{
    /** 버전 수 또는 구버전 합계 용량이 임계를 넘었다. 이 앱의 핵심 발견이다. */
    OLD_VERSIONS("oldversions", 1),

    /** 같은 내용의 파일이 인스턴스 안에 두 개 이상 있다. */
    DUPLICATE("duplicate", 2),

    /** 최신 버전 단독 크기가 임계를 넘었다. */
    LARGE("large", 4),

    /** 마지막 수정 후 오래됐다. */
    STALE("stale", 8);

    private final String code;
    private final int bit;

    Badge(String code, int bit)
    {
        this.code = code;
        this.bit = bit;
    }

    public String code()
    {
        return code;
    }

    /** AO 에 정수 하나로 담기 위한 비트. 배지가 늘면 다음 2의 거듭제곱을 쓴다. */
    public int bit()
    {
        return bit;
    }

    public boolean isIn(int bits)
    {
        return (bits & bit) != 0;
    }

    public String i18nKey(String part)
    {
        return "aj.badge." + part + "." + code;
    }

    public static int bitsOf(Badge... badges)
    {
        int bits = 0;
        for (Badge badge : badges)
        {
            bits |= badge.bit;
        }
        return bits;
    }

    public static Badge byCode(String code)
    {
        for (Badge badge : values())
        {
            if (badge.code.equals(code))
            {
                return badge;
            }
        }
        return null;
    }
}
