package co.bskim.confluence.attachjanitor.rest;

/**
 * A very small JSON writer.
 *
 * <p>The responses here are flat objects of strings and numbers, so a library would be a
 * bundled dependency and an OSGi import for the sake of six field types. What is not
 * negotiable is the escaping, which is why it is in one place with a test.</p>
 */
public final class Json
{
    private final StringBuilder out = new StringBuilder(256);
    private boolean needComma;

    public static Json object()
    {
        Json json = new Json();
        json.out.append('{');
        return json;
    }

    public static Json array()
    {
        Json json = new Json();
        json.out.append('[');
        return json;
    }

    public Json put(String name, String value)
    {
        key(name);
        writeString(value);
        return this;
    }

    public Json put(String name, long value)
    {
        key(name);
        out.append(value);
        return this;
    }

    public Json put(String name, int value)
    {
        key(name);
        out.append(value);
        return this;
    }

    public Json put(String name, boolean value)
    {
        key(name);
        out.append(value);
        return this;
    }

    /** Writes an already-rendered object or array as the value. */
    public Json putRaw(String name, String json)
    {
        key(name);
        out.append(json == null ? "null" : json);
        return this;
    }

    /** Appends an already-rendered element to an array. */
    public Json add(String json)
    {
        comma();
        out.append(json == null ? "null" : json);
        return this;
    }

    public String end()
    {
        char opener = out.charAt(0);
        out.append(opener == '{' ? '}' : ']');
        return out.toString();
    }

    private void key(String name)
    {
        comma();
        writeString(name);
        out.append(':');
    }

    private void comma()
    {
        if (needComma)
        {
            out.append(',');
        }
        needComma = true;
    }

    private void writeString(String value)
    {
        if (value == null)
        {
            out.append("null");
            return;
        }
        out.append('"');
        for (int index = 0; index < value.length(); index++)
        {
            char character = value.charAt(index);
            switch (character)
            {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    // Control characters and U+2028/U+2029 are escaped: the second pair is legal
                    // JSON but breaks a browser that evaluates the payload as JavaScript.
                    if (character < 0x20 || character == 0x2028 || character == 0x2029)
                    {
                        out.append(String.format("\\u%04x", (int) character));
                    }
                    else
                    {
                        out.append(character);
                    }
            }
        }
        out.append('"');
    }
}
