package co.bskim.confluence.attachjanitor.analyze;

import co.bskim.confluence.attachjanitor.model.AttachmentFacts;
import co.bskim.confluence.attachjanitor.model.Badge;
import co.bskim.confluence.attachjanitor.settings.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 같은 내용의 파일을 찾는다. <b>반드시 2단계로 간다</b>(기획서 4.3).
 *
 * <ol>
 *   <li>(크기, 확장자)가 같은 것끼리만 후보 그룹을 만든다. 대부분 여기서 단독이 되어
 *       떨어진다 — 크기가 다르면 내용이 같을 수 없으므로 이 단계는 오탐이 없다.</li>
 *   <li>후보에 속한 파일만 해시한다.</li>
 * </ol>
 *
 * <p>1단계를 건너뛰고 전부 해시하면 첨부 전체를 디스크에서 읽게 된다. 조회 전용 앱이
 * 인스턴스 I/O 를 통째로 먹는 것은 받아들일 수 없다.
 *
 * <p><b>약식 해시는 "중복"이라고 말하지 않는다.</b> 기본값인 약식 모드는 파일 크기와
 * 앞 64KB 만 읽으므로 같다고 <em>단정</em>할 수 없다. 그 경우 결과는 "중복 후보"로 표기한다 —
 * 확인하지 않은 것을 확인했다고 말하지 않는다는 이 앱의 원칙이다.
 *
 * <p>읽는 바이트에는 상한이 있다. 상한에 걸리면 남은 후보를 조용히 "중복 아님"으로
 * 두지 않고 {@link Result#budgetHit} 로 알린다. 검사하지 못한 것은 없는 것이 아니다.
 */
public final class DuplicateFinder
{
    /** 약식 해시가 읽는 파일 앞부분의 크기. */
    private static final int SAMPLE = 64 * 1024;

    /** 파일 바이트를 읽는 통로. 스캔이 Confluence 를 넘겨주고 테스트는 가짜를 넘긴다. */
    public interface DataSource
    {
        InputStream open(long attachmentId) throws IOException;
    }

    public static final class Group
    {
        public final String hash;
        public final long unitBytes;
        public final List<AttachmentFacts> members;
        public final boolean exact;

        Group(String hash, long unitBytes, List<AttachmentFacts> members, boolean exact)
        {
            this.hash = hash;
            this.unitBytes = unitBytes;
            this.members = members;
            this.exact = exact;
        }

        /** 한 벌만 남기면 아낄 수 있는 용량. 관리자가 실제로 보고 싶은 숫자다. */
        public long reclaimableBytes()
        {
            return unitBytes * (members.size() - 1);
        }
    }

    public static final class Result
    {
        public final List<Group> groups = new ArrayList<Group>();
        public long hashedBytes;
        public boolean budgetHit;
        /** 읽다 실패한 첨부 수. 파일이 없거나 권한이 없을 때. */
        public int unreadable;

        public long reclaimableBytes()
        {
            long total = 0;
            for (Group group : groups)
            {
                total += group.reclaimableBytes();
            }
            return total;
        }
    }

    private DuplicateFinder()
    {
    }

    public static Result find(List<AttachmentFacts> all, Settings settings, DataSource data)
    {
        Result result = new Result();
        boolean exact = settings.duplicateMode == Settings.DuplicateMode.FULL;

        // 1단계: 크기와 확장자가 같은 것끼리 묶는다. 여기서 단독인 것은 검사하지 않는다.
        Map<String, List<AttachmentFacts>> candidates =
                new HashMap<String, List<AttachmentFacts>>();
        for (AttachmentFacts facts : all)
        {
            if (facts.latestBytes <= 0)
            {
                continue;                 // 빈 파일끼리 "중복"이라고 하는 것은 잡음이다
            }
            String key = facts.latestBytes + "/" + facts.extension;
            List<AttachmentFacts> bucket = candidates.get(key);
            if (bucket == null)
            {
                bucket = new ArrayList<AttachmentFacts>();
                candidates.put(key, bucket);
            }
            bucket.add(facts);
        }

        // 2단계: 후보에 속한 것만 해시한다.
        Map<String, List<AttachmentFacts>> byHash = new HashMap<String, List<AttachmentFacts>>();
        for (Map.Entry<String, List<AttachmentFacts>> entry : candidates.entrySet())
        {
            List<AttachmentFacts> bucket = entry.getValue();
            if (bucket.size() < 2)
            {
                continue;
            }
            for (AttachmentFacts facts : bucket)
            {
                long want = exact ? facts.latestBytes : Math.min(facts.latestBytes, SAMPLE);
                if (settings.duplicateByteBudget > 0
                        && result.hashedBytes + want > settings.duplicateByteBudget)
                {
                    result.budgetHit = true;
                    continue;
                }
                String hash = hash(facts, exact, data, result);
                if (hash == null)
                {
                    continue;
                }
                facts.contentHash = hash;
                List<AttachmentFacts> same = byHash.get(hash);
                if (same == null)
                {
                    same = new ArrayList<AttachmentFacts>();
                    byHash.put(hash, same);
                }
                same.add(facts);
            }
        }

        for (Map.Entry<String, List<AttachmentFacts>> entry : byHash.entrySet())
        {
            List<AttachmentFacts> members = entry.getValue();
            if (members.size() < 2)
            {
                // 크기는 같았지만 내용이 달랐다. 1단계 후보가 2단계에서 떨어진 정상 경로다.
                for (AttachmentFacts facts : members)
                {
                    facts.contentHash = null;
                }
                continue;
            }
            for (AttachmentFacts facts : members)
            {
                facts.badges |= Badge.DUPLICATE.bit();
            }
            result.groups.add(new Group(entry.getKey(), members.get(0).latestBytes,
                    members, exact));
        }
        return result;
    }

    /**
     * 해시 하나. 실패하면 null 을 돌려주고 호출자가 "읽지 못함"으로 센다.
     *
     * <p>약식 모드는 <b>파일 크기 + 앞 64KB</b> 만 본다. 뒤쪽 블록까지 섞으려면 파일을
     * 끝까지 흘려야 하는데(스트림에 seek 가 없다) 그러면 전체 해시와 I/O 가 같아져
     * 약식일 이유가 없어진다. 대신 결과를 "중복"이 아니라 "중복 후보"로 표기한다.
     */
    private static String hash(AttachmentFacts facts, boolean exact, DataSource data,
                               Result result)
    {
        InputStream stream = null;
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(Long.toString(facts.latestBytes).getBytes("UTF-8"));
            stream = data.open(facts.attachmentId);
            byte[] buffer = new byte[8192];
            long limit = exact ? Long.MAX_VALUE : SAMPLE;
            long read = 0;
            int count;
            while (read < limit && (count = stream.read(buffer)) != -1)
            {
                int use = (int) Math.min(count, limit - read);
                digest.update(buffer, 0, use);
                read += count;
                result.hashedBytes += count;
            }
            return (exact ? "s1:" : "q1:") + toHex(digest.digest());
        }
        catch (NoSuchAlgorithmException error)
        {
            return null;                 // SHA-1 없는 JVM 은 없다
        }
        catch (Throwable error)
        {
            // 파일이 사라졌거나 못 읽는다. 한 건 때문에 중복 판정 전체를 버리지 않는다.
            result.unreadable++;
            return null;
        }
        finally
        {
            if (stream != null)
            {
                try
                {
                    stream.close();
                }
                catch (IOException ignored)
                {
                    // 읽었거나 못 읽었거나 둘 중 하나다.
                }
            }
        }
    }

    private static String toHex(byte[] bytes)
    {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
        {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }
}
