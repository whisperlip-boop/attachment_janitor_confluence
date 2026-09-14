package co.bskim.confluence.attachjanitor.model;

/**
 * 참조가 발견된 자리의 종류. 화면에서 "어디서 쓰이는지"를 사람 말로 보여주는 데 쓴다.
 *
 * <p>라벨의 근거다. 근거를 펼쳐 볼 수 없는 라벨은 신뢰받지 못한다 — [위험] 이라고만
 * 적혀 있으면 관리자는 어느 페이지가 깨지는지 알 수 없어서 아무 조치도 못 한다.
 */
public enum RefKind
{
    /** 첨부가 붙어 있는 바로 그 컨테이너의 현재 본문. */
    OWN_BODY("own"),
    /** 다른 페이지·블로그의 현재 본문. */
    OTHER_BODY("other"),
    /** 댓글 본문. 댓글은 자기 페이지의 첨부를 가리키는 경우가 많다. */
    COMMENT("comment"),
    /** 스페이스 설명. 본문 형식이 wiki 라 따로 읽는다(실측 10번). */
    SPACE_DESCRIPTION("spacedesc"),
    /** 과거 버전 본문. 이력 스캔을 켰을 때만 채워진다. */
    HISTORY("history"),
    /**
     * 스페이스에 설정된 로고.
     *
     * <p>본문에서 찾은 참조가 <b>아니다.</b> 로고는 SPACEDESCRIPTION 컨테이너에 붙은
     * 첨부이고 어느 본문도 그것을 가리키지 않는다(실측 27번). 그대로 두면 앱이 스페이스
     * 로고를 [고아] 로 찍어 "아무도 안 쓴다"고 말하게 된다 — 지우면 스페이스 머리글이
     * 깨지는데도. 그래서 스캔이 스페이스 설정을 보고 근거를 하나 만들어 붙인다.
     */
    SPACE_LOGO("spacelogo");

    private final String code;

    RefKind(String code)
    {
        this.code = code;
    }

    public String code()
    {
        return code;
    }

    public String i18nKey()
    {
        return "aj.ref.kind." + code;
    }
}
