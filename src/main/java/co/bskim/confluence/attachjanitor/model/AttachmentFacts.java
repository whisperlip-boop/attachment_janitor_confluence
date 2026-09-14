package co.bskim.confluence.attachjanitor.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 스캔이 첨부 하나에 대해 모은 것 전부. 저장 직전까지 메모리에 들고 있는 모양이다. */
public final class AttachmentFacts
{
    public long attachmentId;
    public String fileName = "";
    public String extension = "";
    public String spaceKey = "";
    public String spaceName = "";
    public long containerId;
    public String containerTitle = "";
    public String containerType = "";
    public String containerStatus = "";
    public long latestBytes;
    public int versionCount;
    public long oldVersionBytes;
    public int oldVersionCount;
    public Date lastModified;
    public String mediaType = "";

    /** 중복 후보 그룹에 든 경우에만 채워진다. */
    public String contentHash;

    public Label label;
    public int badges;
    public final List<RefSource> refs = new ArrayList<RefSource>();

    public boolean isNoSpace()
    {
        return spaceKey == null || spaceKey.isEmpty();
    }

    public long totalBytes()
    {
        return latestBytes + oldVersionBytes;
    }

    /** 소문자 확장자. 없으면 빈 문자열. 중복 1단계 후보 묶기에 쓴다. */
    public static String extensionOf(String fileName)
    {
        if (fileName == null)
        {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
