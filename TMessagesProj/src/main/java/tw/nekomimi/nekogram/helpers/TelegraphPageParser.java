package tw.nekomimi.nekogram.helpers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java parser for Telegraph pages (no Android dependencies besides org.json), so it can be
 * sanity-tested off-device. Produces a flat list of image / text items in document order.
 */
public final class TelegraphPageParser {

    public static final String BASE = "https://telegra.ph";

    public static final int ITEM_IMAGE = 0;
    public static final int ITEM_PARAGRAPH = 1;
    public static final int ITEM_HEADING = 2;

    public static final class Item {
        public final int type;
        /** Absolute image url for {@link #ITEM_IMAGE}. */
        public final String src;
        /** Caption for images, text for paragraphs/headings. */
        public final String text;

        Item(int type, String src, String text) {
            this.type = type;
            this.src = src;
            this.text = text;
        }

        @Override
        public String toString() {
            return (type == ITEM_IMAGE ? "IMG " + src + (text.isEmpty() ? "" : " | " + text) : (type == ITEM_HEADING ? "H " : "P ") + text);
        }
    }

    public static final class Result {
        public String title;
        public String author;
        public String url;
        public final List<Item> items = new ArrayList<>();

        public int imageCount() {
            int c = 0;
            for (Item item : items) if (item.type == ITEM_IMAGE) c++;
            return c;
        }
    }

    private TelegraphPageParser() {
    }

    // ---------------------------------------------------------------- API (JSON)

    /** Parses the response of {@code https://api.telegra.ph/getPage/<path>?return_content=true}. */
    public static Result parseApi(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!root.optBoolean("ok")) {
            throw new IllegalStateException("telegraph api error: " + root.optString("error"));
        }
        JSONObject page = root.getJSONObject("result");
        Result result = new Result();
        result.title = emptyToNull(page.optString("title", null));
        result.author = emptyToNull(page.optString("author_name", null));
        result.url = emptyToNull(page.optString("url", null));
        JSONArray content = page.optJSONArray("content");
        if (content != null) {
            walkNodes(content, result.items);
        }
        return result;
    }

    private static void walkNodes(JSONArray nodes, List<Item> out) {
        StringBuilder pendingText = new StringBuilder();
        for (int i = 0; i < nodes.length(); i++) {
            Object node = nodes.opt(i);
            if (node instanceof String) {
                pendingText.append((String) node);
                continue;
            }
            if (!(node instanceof JSONObject)) continue;
            JSONObject o = (JSONObject) node;
            String tag = o.optString("tag").toLowerCase(Locale.ROOT);
            if (isInline(tag) && !hasImage(o)) {
                pendingText.append(textOf(o));
                continue;
            }
            flushText(pendingText, out, ITEM_PARAGRAPH);
            switch (tag) {
                case "img": {
                    addImage(out, attr(o, "src"), "");
                    break;
                }
                case "figure": {
                    String src = null;
                    String caption = "";
                    JSONArray children = o.optJSONArray("children");
                    if (children != null) {
                        for (int j = 0; j < children.length(); j++) {
                            Object c = children.opt(j);
                            if (!(c instanceof JSONObject)) continue;
                            JSONObject child = (JSONObject) c;
                            String ctag = child.optString("tag").toLowerCase(Locale.ROOT);
                            if ("figcaption".equals(ctag)) {
                                caption = textOf(child).trim();
                            } else if (src == null) {
                                src = findImageSrc(child);
                            }
                        }
                    }
                    if (src != null) {
                        addImage(out, src, caption);
                    } else if (!caption.isEmpty()) {
                        // figure with video/iframe only: keep the caption as text
                        out.add(new Item(ITEM_PARAGRAPH, null, caption));
                    }
                    break;
                }
                case "h1":
                case "h2":
                case "h3":
                case "h4":
                case "h5":
                case "h6": {
                    if (hasImage(o)) {
                        walkChildren(o, out);
                    } else {
                        String t = textOf(o).trim();
                        if (!t.isEmpty()) out.add(new Item(ITEM_HEADING, null, t));
                    }
                    break;
                }
                case "br":
                case "hr":
                case "video":
                case "iframe":
                case "script":
                case "style":
                    break;
                default: {
                    // p, blockquote, aside, pre, ul/ol/li, a containing images, unknown containers
                    if (hasImage(o) || hasBlockChildren(o)) {
                        walkChildren(o, out);
                    } else {
                        String t = textOf(o).trim();
                        if (!t.isEmpty()) out.add(new Item(ITEM_PARAGRAPH, null, t));
                    }
                    break;
                }
            }
        }
        flushText(pendingText, out, ITEM_PARAGRAPH);
    }

    private static void walkChildren(JSONObject o, List<Item> out) {
        JSONArray children = o.optJSONArray("children");
        if (children != null) walkNodes(children, out);
    }

    private static void flushText(StringBuilder sb, List<Item> out, int type) {
        String t = sb.toString().trim();
        sb.setLength(0);
        if (!t.isEmpty()) out.add(new Item(type, null, t));
    }

    private static boolean isInline(String tag) {
        switch (tag) {
            case "a":
            case "b":
            case "strong":
            case "i":
            case "em":
            case "u":
            case "s":
            case "code":
            case "span":
            case "br":
                return true;
        }
        return false;
    }

    private static boolean hasBlockChildren(JSONObject o) {
        JSONArray children = o.optJSONArray("children");
        if (children == null) return false;
        for (int i = 0; i < children.length(); i++) {
            Object c = children.opt(i);
            if (c instanceof JSONObject) {
                String tag = ((JSONObject) c).optString("tag").toLowerCase(Locale.ROOT);
                if (!isInline(tag)) return true;
            }
        }
        return false;
    }

    private static boolean hasImage(JSONObject o) {
        return findImageSrc(o) != null;
    }

    private static String findImageSrc(JSONObject o) {
        String tag = o.optString("tag").toLowerCase(Locale.ROOT);
        if ("img".equals(tag)) {
            String src = attr(o, "src");
            return src == null || src.isEmpty() ? null : src;
        }
        JSONArray children = o.optJSONArray("children");
        if (children == null) return null;
        for (int i = 0; i < children.length(); i++) {
            Object c = children.opt(i);
            if (c instanceof JSONObject) {
                String s = findImageSrc((JSONObject) c);
                if (s != null) return s;
            }
        }
        return null;
    }

    private static String attr(JSONObject o, String name) {
        JSONObject attrs = o.optJSONObject("attrs");
        if (attrs == null) return null;
        String v = attrs.optString(name, null);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String textOf(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        appendText(o, sb);
        return sb.toString();
    }

    private static void appendText(Object node, StringBuilder sb) {
        if (node instanceof String) {
            sb.append((String) node);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if ("br".equalsIgnoreCase(o.optString("tag"))) {
                sb.append('\n');
                return;
            }
            JSONArray children = o.optJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) appendText(children.opt(i), sb);
            }
        }
    }

    // ---------------------------------------------------------------- HTML fallback

    private static final Pattern ARTICLE = Pattern.compile("<article[^>]*>(.*?)</article>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile(
            "<figure[^>]*>(.*?)</figure>" +                      // 1: figure body
            "|<img\\b([^>]*)>" +                                 // 2: img attrs
            "|<(h[1-6])[^>]*>(.*?)</\\3>" +                      // 3,4: heading
            "|<address[^>]*>(.*?)</address>" +                   // 5: author
            "|<(p|blockquote|aside|pre|li)[^>]*>(.*?)</\\6>",    // 6,7: text block
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("\\bsrc\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIGCAPTION = Pattern.compile("<figcaption[^>]*>(.*?)</figcaption>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG = Pattern.compile("<img\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /** Fallback: parse the public telegra.ph HTML page. */
    public static Result parseHtml(String html) {
        Result result = new Result();
        Matcher og = OG_TITLE.matcher(html);
        if (og.find()) result.title = emptyToNull(unescape(og.group(1)).trim());
        Matcher am = ARTICLE.matcher(html);
        String body = am.find() ? am.group(1) : html;
        Matcher m = TOKEN.matcher(body);
        boolean titleSeen = false;
        while (m.find()) {
            if (m.group(1) != null) {
                String fig = m.group(1);
                Matcher img = IMG.matcher(fig);
                String src = img.find() ? srcOf(img.group(1)) : null;
                Matcher cap = FIGCAPTION.matcher(fig);
                String caption = cap.find() ? htmlToText(cap.group(1)) : "";
                if (src != null) {
                    addImage(result.items, src, caption);
                } else if (!caption.isEmpty()) {
                    result.items.add(new Item(ITEM_PARAGRAPH, null, caption));
                }
            } else if (m.group(2) != null) {
                String src = srcOf(m.group(2));
                if (src != null) addImage(result.items, src, "");
            } else if (m.group(3) != null) {
                String text = htmlToText(m.group(4));
                if ("h1".equalsIgnoreCase(m.group(3)) && !titleSeen) {
                    // the first h1 inside the article is the page title
                    titleSeen = true;
                    if (!text.isEmpty()) result.title = text;
                } else if (!text.isEmpty()) {
                    result.items.add(new Item(ITEM_HEADING, null, text));
                }
            } else if (m.group(5) != null) {
                if (result.author == null) result.author = emptyToNull(htmlToText(m.group(5)));
            } else if (m.group(6) != null) {
                String inner = m.group(7);
                Matcher img = IMG.matcher(inner);
                while (img.find()) {
                    String src = srcOf(img.group(1));
                    if (src != null) addImage(result.items, src, "");
                }
                String text = htmlToText(inner);
                if (!text.isEmpty()) result.items.add(new Item(ITEM_PARAGRAPH, null, text));
            }
        }
        return result;
    }

    private static String srcOf(String attrs) {
        Matcher s = SRC.matcher(attrs);
        if (!s.find()) return null;
        String v = s.group(2) != null ? s.group(2) : s.group(3) != null ? s.group(3) : s.group(4);
        v = unescape(v).trim();
        return v.isEmpty() ? null : v;
    }

    private static String htmlToText(String html) {
        String t = BR.matcher(html).replaceAll("\n");
        t = TAG.matcher(t).replaceAll("");
        return unescape(t).trim();
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    // ---------------------------------------------------------------- helpers

    private static void addImage(List<Item> out, String src, String caption) {
        String abs = absolutize(src);
        if (abs != null) out.add(new Item(ITEM_IMAGE, abs, caption == null ? "" : caption.trim()));
    }

    /** {@code /file/x.jpg} -> {@code https://telegra.ph/file/x.jpg}, {@code //host/x} -> {@code https://host/x}. */
    public static String absolutize(String src) {
        if (src == null) return null;
        src = src.trim();
        if (src.isEmpty() || src.startsWith("data:")) return null;
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("/")) return BASE + src;
        String lower = src.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return src;
        return BASE + "/" + src;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }
}
