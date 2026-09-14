package co.bskim.confluence.attachjanitor.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Reads the app's own CSS/JS off the classpath so the servlet can inline it.
 *
 * <p>Inlining rather than serving a web-resource keeps the admin page working on a first load
 * after an upgrade, when the browser may still hold the previous build's file at an unchanged
 * immutable URL.</p>
 */
public final class StaticAssets
{
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private StaticAssets()
    {
    }

    public static String read(String path)
    {
        InputStream stream = StaticAssets.class.getResourceAsStream(path);
        if (stream == null)
        {
            return "";
        }
        try
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1)
            {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        }
        catch (IOException error)
        {
            return "";
        }
        finally
        {
            try
            {
                stream.close();
            }
            catch (IOException ignored)
            {
                // Nothing useful to do; the content was either read or it was not.
            }
        }
    }

    /** HTML text escaping. Everything the servlet interpolates goes through this. */
    public static String escape(String text)
    {
        if (text == null)
        {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++)
        {
            char character = text.charAt(index);
            switch (character)
            {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(character);
            }
        }
        return out.toString();
    }
}
