package co.bskim.confluence.attachjanitor.analyze;

import co.bskim.confluence.attachjanitor.model.AttachmentRef;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.EntityResolver;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문 하나에서 첨부 참조를 전부 뽑는다.
 *
 * <p><b>매크로 이름을 열거하지 않는다.</b> 실측(V5)에서 {@code viewfile} ·
 * {@code multimedia} · {@code viewpdf} · 이미지 · 링크가 전부 같은 {@code ri:attachment}
 * 엘리먼트를 쓴다는 것을 확인했다. 그래서 트리 어디에 있든 그 엘리먼트를 전부 걷는다 —
 * 우리가 모르는 매크로도 자동으로 잡히고, 매크로 목록을 관리할 필요가 없다.
 *
 * <p>예외는 {@code gallery} 하나다. {@code include} / {@code exclude} 파라미터가
 * 엘리먼트가 아니라 콤마로 이어진 <b>평문 문자열</b>이라 순회로는 안 잡힌다.
 *
 * <p>본문 형식이 둘이라는 것도 실측이다(10번). 페이지·블로그·댓글은 XHTML 저장 형식이고
 * <b>스페이스 설명은 wiki</b> 다. 둘을 같은 파서에 넣으면 스페이스 설명은 전부 분석
 * 실패로 잡힌다.
 */
public final class ReferenceScanner
{
    /** 이 프리픽스로 감싸 여러 개의 최상위 엘리먼트를 하나의 문서로 만든다. */
    private static final String WRAP_OPEN = "<aj-body>";
    private static final String WRAP_CLOSE = "</aj-body>";

    /** wiki 의 {@code !space:page^file.png!} / {@code !file.png!} */
    private static final Pattern WIKI_IMAGE = Pattern.compile("!([^!\\n|]+?)(?:\\|[^!\\n]*)?!");
    /** wiki 의 {@code [^file.pdf]} / {@code [space:page^file.pdf]} */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[([^\\]\\n]*\\^[^\\]\\n]+)\\]");
    /** wiki 매크로의 파일명 파라미터: {@code {viewfile:name=x}} {@code {gallery:include=a,b}} */
    private static final Pattern WIKI_MACRO =
            Pattern.compile("\\{(viewfile|viewpdf|viewdoc|viewxls|viewppt|multimedia|gallery)"
                    + ":([^}]*)\\}");

    private ReferenceScanner()
    {
    }

    /** 한 본문의 분석 결과. 실패를 조용히 빈 목록으로 돌려주지 않는다. */
    public static final class Result
    {
        public final List<AttachmentRef> refs;
        public final boolean parsed;

        Result(List<AttachmentRef> refs, boolean parsed)
        {
            this.refs = refs;
            this.parsed = parsed;
        }
    }

    /** XHTML 저장 형식(페이지 · 블로그 · 댓글). */
    public static Result scanStorage(String body)
    {
        List<AttachmentRef> refs = new ArrayList<AttachmentRef>();
        if (body == null || body.trim().isEmpty())
        {
            return new Result(refs, true);
        }
        Document document;
        try
        {
            document = parse(WRAP_OPEN + HtmlEntities.neutralise(body) + WRAP_CLOSE);
        }
        catch (Throwable error)
        {
            // 못 읽은 본문은 "참조 없음"이 아니다. 호출자가 실패로 집계해 화면에 낸다.
            return new Result(refs, false);
        }
        collectAttachmentElements(document.getDocumentElement(), refs);
        collectGalleryParameters(document.getDocumentElement(), refs);
        return new Result(refs, true);
    }

    /** wiki 형식(스페이스 설명). 저장 형식보다 거칠게 읽는다 — 그래서 별도 메서드다. */
    public static Result scanWiki(String body)
    {
        List<AttachmentRef> refs = new ArrayList<AttachmentRef>();
        if (body == null || body.trim().isEmpty())
        {
            return new Result(refs, true);
        }
        Matcher image = WIKI_IMAGE.matcher(body);
        while (image.find())
        {
            addWikiTarget(refs, image.group(1));
        }
        Matcher link = WIKI_LINK.matcher(body);
        while (link.find())
        {
            addWikiTarget(refs, link.group(1));
        }
        Matcher macro = WIKI_MACRO.matcher(body);
        while (macro.find())
        {
            for (String part : macro.group(2).split("[,|]"))
            {
                int equals = part.indexOf('=');
                String value = equals < 0 ? part : part.substring(equals + 1);
                if (looksLikeFileName(value))
                {
                    refs.add(new AttachmentRef(value, null, null));
                }
            }
        }
        return new Result(refs, true);
    }

    /**
     * {@code space:page^file} · {@code page^file} · {@code file} 세 모양을 가른다.
     * wiki 표기는 이 세 가지가 전부다.
     */
    private static void addWikiTarget(List<AttachmentRef> refs, String raw)
    {
        String value = raw.trim();
        String fileName = value;
        String title = null;
        String spaceKey = null;

        int caret = value.indexOf('^');
        if (caret >= 0)
        {
            fileName = value.substring(caret + 1);
            String owner = value.substring(0, caret);
            int colon = owner.indexOf(':');
            if (colon >= 0)
            {
                spaceKey = owner.substring(0, colon);
                title = owner.substring(colon + 1);
            }
            else
            {
                title = owner;
            }
        }
        if (looksLikeFileName(fileName))
        {
            refs.add(new AttachmentRef(fileName, spaceKey, title));
        }
    }

    /** 확장자가 있어야 파일명으로 본다. {@code !색상!} 같은 wiki 강조를 걸러낸다. */
    private static boolean looksLikeFileName(String value)
    {
        String trimmed = value == null ? "" : value.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot > 0 && dot < trimmed.length() - 1 && trimmed.indexOf(' ') != 0
                && !trimmed.startsWith("http");
    }

    private static void collectAttachmentElements(Node node, List<AttachmentRef> refs)
    {
        if (node.getNodeType() == Node.ELEMENT_NODE && isNamed(node, "attachment"))
        {
            Element element = (Element) node;
            String fileName = attribute(element, "filename");
            if (fileName != null && !fileName.isEmpty())
            {
                // <ri:page> 자식이 있으면 다른 페이지의 첨부다. 없으면 자기 컨테이너다.
                String spaceKey = null;
                String title = null;
                NodeList children = element.getChildNodes();
                for (int index = 0; index < children.getLength(); index++)
                {
                    Node child = children.item(index);
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && (isNamed(child, "page") || isNamed(child, "blog-post")
                                || isNamed(child, "content-entity")))
                    {
                        spaceKey = attribute((Element) child, "space-key");
                        title = attribute((Element) child, "content-title");
                        break;
                    }
                }
                refs.add(new AttachmentRef(fileName, spaceKey, title));
            }
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++)
        {
            collectAttachmentElements(children.item(index), refs);
        }
    }

    /**
     * {@code gallery} 매크로만 파라미터 문자열을 쪼갠다.
     *
     * <p>gallery 는 자기 페이지의 첨부만 다루므로 대상은 언제나 본문의 컨테이너다.
     * {@code exclude} 도 읽는 이유: 이름이 적혀 있다는 것은 그 파일을 알고 있다는 뜻이고,
     * 빼라고 적힌 파일을 [고아] 로 찍으면 관리자가 지운 뒤 gallery 가 그 파일을 다시
     * 보여주게 된다.
     */
    private static void collectGalleryParameters(Node node, List<AttachmentRef> refs)
    {
        if (node.getNodeType() == Node.ELEMENT_NODE && isNamed(node, "structured-macro")
                && "gallery".equals(attribute((Element) node, "name")))
        {
            NodeList children = ((Element) node).getElementsByTagName("*");
            for (int index = 0; index < children.getLength(); index++)
            {
                Node child = children.item(index);
                if (!isNamed(child, "parameter"))
                {
                    continue;
                }
                String parameter = attribute((Element) child, "name");
                if (!"include".equals(parameter) && !"exclude".equals(parameter))
                {
                    continue;
                }
                for (String part : child.getTextContent().split(","))
                {
                    String fileName = part.trim();
                    if (!fileName.isEmpty())
                    {
                        refs.add(new AttachmentRef(fileName, null, null));
                    }
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++)
        {
            collectGalleryParameters(children.item(index), refs);
        }
    }

    /** 프리픽스를 무시하고 이름만 본다 — 파서를 네임스페이스 비인식으로 돌리기 때문이다. */
    private static boolean isNamed(Node node, String localName)
    {
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return localName.equals(colon < 0 ? name : name.substring(colon + 1));
    }

    private static String attribute(Element element, String localName)
    {
        NamedNodeMap attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++)
        {
            Attr attribute = (Attr) attributes.item(index);
            String name = attribute.getName();
            int colon = name.indexOf(':');
            if (localName.equals(colon < 0 ? name : name.substring(colon + 1)))
            {
                return attribute.getValue();
            }
        }
        return null;
    }

    /**
     * 파서를 만든다.
     *
     * <p><b>네임스페이스 비인식으로 돌린다.</b> 저장 형식 조각에는 {@code ac:} /
     * {@code ri:} 프리픽스가 선언 없이 들어 있어서 네임스페이스 인식 파서는 바로 죽는다.
     * 비인식 모드에서는 프리픽스가 그냥 이름의 일부라 아무 선언 없이 읽힌다.
     *
     * <p>DTD 와 외부 엔티티는 전부 막는다. 남이 쓴 문서를 파싱하는 코드이므로 XXE 를
     * 열어 둘 이유가 없다.
     */
    private static Document parse(String xml) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(new EntityResolver()
        {
            @Override
            public InputSource resolveEntity(String publicId, String systemId)
            {
                return new InputSource(new StringReader(""));
            }
        });
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value)
    {
        try
        {
            factory.setFeature(feature, value);
        }
        catch (Exception ignored)
        {
            // 파서 구현마다 아는 기능이 다르다. 모르는 것은 넘어간다.
        }
    }
}
