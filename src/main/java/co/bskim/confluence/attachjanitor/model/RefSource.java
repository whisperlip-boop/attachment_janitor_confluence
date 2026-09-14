package co.bskim.confluence.attachjanitor.model;

/** 참조가 발견된 자리 하나. 화면에서 "어디서 쓰이는지"로 보여준다. */
public final class RefSource
{
    public final RefKind kind;
    public final long contentId;
    public final String title;
    public final String spaceKey;
    public final String contentType;

    /**
     * 이 참조가 첨부가 붙어 있는 바로 그 컨테이너 안에서 나왔는가.
     *
     * <p>같은 페이지의 댓글이 그 페이지의 첨부를 쓰는 것과, <b>다른</b> 페이지가 쓰는 것은
     * 위험도가 다르다. 앞은 지울 때 눈에 보이고 뒤는 안 보인다. 라벨이 갈리는 지점이라
     * 종류와 별도로 들고 있다.
     */
    public final boolean sameContainer;

    public RefSource(RefKind kind, long contentId, String title, String spaceKey,
                     String contentType)
    {
        this(kind, contentId, title, spaceKey, contentType, false);
    }

    private RefSource(RefKind kind, long contentId, String title, String spaceKey,
                      String contentType, boolean sameContainer)
    {
        this.kind = kind;
        this.contentId = contentId;
        this.title = title == null ? "" : title;
        this.spaceKey = spaceKey == null ? "" : spaceKey;
        this.contentType = contentType == null ? "" : contentType;
        this.sameContainer = sameContainer;
    }

    /**
     * 대상이 풀린 뒤에야 알 수 있는 값이라 여기서 새로 만든다.
     * 종류도 함께 바로잡는다 — 다른 페이지의 본문이면 {@link RefKind#OTHER_BODY} 다.
     */
    public RefSource resolvedAgainst(boolean same)
    {
        RefKind actual = kind;
        if (!same && kind == RefKind.OWN_BODY)
        {
            actual = RefKind.OTHER_BODY;
        }
        return new RefSource(actual, contentId, title, spaceKey, contentType, same);
    }
}
