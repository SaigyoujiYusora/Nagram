package tw.nekomimi.nekogram.helpers;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.web.WebInstantView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

import xyz.nextalone.nagram.NaConfig;

/**
 * Forces Instant View for telegra.ph / te.legra.ph / graph.org pages that Telegram has no
 * Instant View for (typically image-only pages). The page is fetched through the Telegraph API
 * (falling back to the public HTML) and rendered as a locally built {@link TL_iv.TL_page}.
 */
public final class TelegraphInstantView {

    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private TelegraphInstantView() {
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getForceTelegraphInstantView().Bool();
    }

    /** @return the Telegraph page path (decoded) or null when the url is not a Telegraph article. */
    public static String getPagePath(Uri uri) {
        if (uri == null) return null;
        String host = uri.getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (!host.equals("telegra.ph") && !host.equals("te.legra.ph") && !host.equals("graph.org")) {
            return null;
        }
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 1) return null;
        String path = segments.get(0);
        if (TextUtils.isEmpty(path)) return null;
        switch (path.toLowerCase(Locale.ROOT)) {
            case "api":
            case "upload":
            case "edit":
            case "file":
            case "js":
            case "css":
            case "images":
            case "favicon.ico":
            case "robots.txt":
                return null;
        }
        return path;
    }

    /**
     * Starts loading a forced Instant View for {@code uri}.
     *
     * @param onLoadingEnd called on the UI thread once loading finished (success or failure), before opening
     * @param fallback     called on the UI thread when the page couldn't be loaded (e.g. open in browser)
     * @return a cancel handle, or null when the url is not handled (feature off / not a Telegraph article)
     */
    public static Runnable tryOpen(int currentAccount, Uri uri, Runnable onLoadingEnd, Runnable fallback) {
        if (!isEnabled()) return null;
        final String path = getPagePath(uri);
        if (path == null) return null;
        final String url = uri.toString();
        final boolean[] cancelled = new boolean[]{false};
        Utilities.globalQueue.postRunnable(() -> {
            TelegraphPageParser.Result result = null;
            try {
                String json = httpGet("https://api.telegra.ph/getPage/" + Uri.encode(path) + "?return_content=true");
                result = TelegraphPageParser.parseApi(json);
            } catch (Throwable e) {
                FileLog.e("TelegraphInstantView: api failed for " + path, e);
            }
            if (result == null || result.imageCount() == 0) {
                try {
                    String html = httpGet("https://telegra.ph/" + Uri.encode(path));
                    TelegraphPageParser.Result htmlResult = TelegraphPageParser.parseHtml(html);
                    if (result == null || htmlResult.imageCount() > 0) {
                        result = htmlResult;
                    }
                } catch (Throwable e) {
                    FileLog.e("TelegraphInstantView: html failed for " + path, e);
                }
            }
            final TelegraphPageParser.Result finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (onLoadingEnd != null) onLoadingEnd.run();
                if (cancelled[0]) return;
                TLRPC.TL_webPage webPage = null;
                if (finalResult != null && finalResult.imageCount() > 0) {
                    try {
                        webPage = buildWebPage(url, finalResult);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
                if (webPage != null) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.openArticle, webPage, url);
                } else if (fallback != null) {
                    fallback.run();
                }
            });
        });
        return () -> cancelled[0] = true;
    }

    public static TLRPC.TL_webPage buildWebPage(String url, TelegraphPageParser.Result result) {
        final WebInstantView instant = new WebInstantView();
        instant.url = url;
        instant.useImageLoader = true;

        final TL_iv.TL_page page = new TL_iv.TL_page();
        page.web = true;
        page.url = url;

        final TL_iv.pageBlockTitle title = new TL_iv.pageBlockTitle();
        title.text = plain(TextUtils.isEmpty(result.title) ? "Telegraph" : result.title);
        page.blocks.add(title);
        if (!TextUtils.isEmpty(result.author)) {
            final TL_iv.pageBlockAuthorDate authorDate = new TL_iv.pageBlockAuthorDate();
            authorDate.author = plain(result.author);
            page.blocks.add(authorDate);
        }

        for (TelegraphPageParser.Item item : result.items) {
            if (item.type == TelegraphPageParser.ITEM_IMAGE) {
                final WebInstantView.WebPhoto photo = instant.new WebPhoto();
                photo.instantView = instant;
                photo.id = -1 - page.photos.size();
                photo.url = item.src;
                photo.urls.add(item.src);
                page.photos.add(photo);

                final TL_iv.pageBlockPhoto block = new TL_iv.pageBlockPhoto();
                block.photo_id = photo.id;
                block.caption = new TL_iv.PageCaption();
                block.caption.text = TextUtils.isEmpty(item.text) ? new TL_iv.textEmpty() : plain(item.text);
                block.caption.credit = new TL_iv.textEmpty();
                page.blocks.add(block);
            } else if (item.type == TelegraphPageParser.ITEM_HEADING) {
                final TL_iv.pageBlockHeader header = new TL_iv.pageBlockHeader();
                header.text = plain(item.text);
                page.blocks.add(header);
            } else {
                final TL_iv.pageBlockParagraph paragraph = new TL_iv.pageBlockParagraph();
                paragraph.text = plain(item.text);
                page.blocks.add(paragraph);
            }
        }

        final TLRPC.TL_webPage webPage = new TLRPC.TL_webPage();
        webPage.id = -(0x7f000000L + (url.hashCode() & 0xffffffffL));
        webPage.url = url;
        webPage.display_url = url.replaceFirst("^https?://", "");
        webPage.type = "telegraph";
        webPage.flags |= 2 | 4 | 1024;
        webPage.site_name = "Telegraph";
        webPage.title = TextUtils.isEmpty(result.title) ? "Telegraph" : result.title;
        if (!TextUtils.isEmpty(result.author)) {
            webPage.flags |= 256;
            webPage.author = result.author;
        }
        webPage.cached_page = page;

        instant.webpage = webPage;
        WebInstantView.instants.put(webPage, instant);
        return webPage;
    }

    private static TL_iv.RichText plain(String text) {
        final TL_iv.textPlain t = new TL_iv.textPlain();
        t.text = text;
        return t;
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Nagram");
            connection.setRequestProperty("Accept-Language", "en");
            final int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " for " + urlString);
            }
            try (InputStream in = connection.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) {
                        throw new IllegalStateException("response too large");
                    }
                }
                return out.toString("UTF-8");
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
