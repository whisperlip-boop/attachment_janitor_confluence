package co.bskim.confluence.attachjanitor.model;

/**
 * 첨부 하나가 받는 상태 라벨. 축은 하나다 — **지우면 뭐가 깨지는가.**
 *
 * <p>"얼마나 큰가"는 라벨이 아니라 {@link Badge} 다. 두 축을 한 라벨에 섞으면
 * "중복이면서 고아"이거나 "활성이면서 구버전 20개"인 첨부를 표현할 수 없다.
 *
 * <p>기획서 3.1 은 라벨 다섯 개를 <em>활성 · 위험 · 이력 참조 · 고아 · 휴지통</em> 으로
 * 적었다. 실측 후 <b>휴지통이 빠지고 초안이 들어왔다.</b> 근거는 docs/00-환경실측.md 다.
 * <ul>
 *   <li>휴지통 — 페이지를 휴지통에 넣으면 그 첨부도 {@code deleted} 가 되는데(V7),
 *       스캔이 쓰는 이터레이터는 {@code deleted} 첨부를 아예 돌려주지 않는다(실측 2번).
 *       즉 이 라벨은 <b>구조적으로 한 번도 나올 수 없다.</b> 나올 수 없는 라벨을
 *       설명서에 적어 두면 관리자는 "우리 인스턴스엔 휴지통 첨부가 없구나"로 읽는다.</li>
 *   <li>초안 — 초안 페이지에 붙은 첨부가 실재한다(V6). 초안 본문은 저장되지 않았고 다른
 *       사람에게 보이지 않으므로 참조로 세지 않는데, 그렇다고 [고아] 로 찍으면
 *       "지워도 된다"는 뜻이 되어 거짓말이 된다.</li>
 * </ul>
 *
 * <p>새 라벨을 넣거나 빼면 설명서 표와 필터가 자동으로 따라온다 — 둘 다 이 열거형을
 * 순회해서 만든다. i18n 키만 채우면 된다.
 */
public enum Label
{
    /** 자기 컨테이너의 현재 본문에서 참조된다. 쓰이고 있다. */
    ACTIVE("active", 40),

    /**
     * 자기 컨테이너가 아닌 곳에서 참조된다(다른 페이지 · 댓글 · 스페이스 설명 등).
     * 지우면 남의 페이지가 조용히 깨진다. 첨부가 붙은 페이지만 보고 판단하면 안 된다.
     */
    RISKY("risky", 50),

    /**
     * 현재 본문 어디에도 없고 과거 버전 본문에만 있다.
     * 지우면 버전 이력이 깨진다 — 과거 버전을 열면 깨진 이미지가 나온다.
     */
    HISTORY("history", 30),

    /** 초안 페이지에 붙어 있다. 저장되면 참조가 생길 수 있으므로 판단을 보류한다. */
    DRAFT("draft", 20),

    /** 이 앱이 본 범위 안에서는 참조가 없다. 정리 1차 대상. */
    ORPHAN("orphan", 10);

    private final String code;
    private final int cleanupRank;

    Label(String code, int cleanupRank)
    {
        this.code = code;
        this.cleanupRank = cleanupRank;
    }

    public String code()
    {
        return code;
    }

    /**
     * 목록 기본 정렬 값. 작을수록 "정리 후보", 클수록 "손대면 위험".
     * 관리자가 목록을 열었을 때 맨 위에 있어야 하는 것은 고아다.
     */
    public int cleanupRank()
    {
        return cleanupRank;
    }

    public String i18nKey(String part)
    {
        return "aj.label." + part + "." + code;
    }

    public static Label byCode(String code)
    {
        for (Label label : values())
        {
            if (label.code.equals(code))
            {
                return label;
            }
        }
        return null;
    }
}
