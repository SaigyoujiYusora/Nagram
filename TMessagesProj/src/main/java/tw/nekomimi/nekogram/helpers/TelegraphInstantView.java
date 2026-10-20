package tw.nekomimi.nekogram.helpers;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.web.WebInstantView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import xyz.nextalone.nagram.NaConfig;

/**
 * Forces Instant View for telegra.ph / te.legra.ph / graph.org pages that Telegram has no
 * Instant View for (typically image-only pages). The page is fetched through the Telegraph API
 * (falling back to the public HTML) and rendered as a locally built {@link TL_iv.TL_page}.
 * <p>
 * Images are not downloaded here — only the page JSON/HTML. ArticleViewer loads image URLs
 * lazily as cells attach (viewport), matching stock Instant View behaviour.
 */
public final class TelegraphInstantView {

    private static final int TIMEOUT_MS = 20000;
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
        if (host == null) {
            // some URIs only have encoded authority in the string
            host = AndroidUtilities.getHostAuthority(uri.toString(), true);
        }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (!host.equals("telegra.ph") && !host.equals("te.legra.ph") && !host.equals("graph.org")) {
            return null;
        }
        final List<String> raw = uri.getPathSegments();
        if (raw == null || raw.isEmpty()) return null;
        final List<String> segments = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (!TextUtils.isEmpty(s)) segments.add(s);
        }
        if (segments.size() != 1) return null;
        String path = segments.get(0);
        if (TextUtils.isEmpty(path)) return null;
        // path segments are decoded; if we somehow got a still-encoded token, decode once
        if (path.indexOf('%') >= 0) {
            try {
                path = Uri.decode(path);
            } catch (Throwable ignore) {
            }
        }
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

    /** True when Telegram's IV for this page is missing or has no photos (image-only pages). */
    public static boolean shouldOverrideTelegramIv(Uri uri, TLRPC.WebPage telegramPage) {
        if (!isEnabled() || getPagePath(uri) == null) return false;
        if (telegramPage == null || telegramPage.cached_page == null) return true;
        return telegramPage.cached_page.photos == null || telegramPage.cached_page.photos.isEmpty();
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
            Throwable lastError = null;
            try {
                String json = httpGetApi(path);
                result = TelegraphPageParser.parseApi(json);
            } catch (Throwable e) {
                lastError = e;
                FileLog.e("TelegraphInstantView: api failed for " + path, e);
            }
            if (result == null || result.imageCount() == 0) {
                try {
                    String html = httpGetHtml(path);
                    TelegraphPageParser.Result htmlResult = TelegraphPageParser.parseHtml(html);
                    if (result == null || htmlResult.imageCount() > result.imageCount()) {
                        result = htmlResult;
                    }
                } catch (Throwable e) {
                    if (lastError == null) lastError = e;
                    FileLog.e("TelegraphInstantView: html failed for " + path, e);
                }
            }
            final TelegraphPageParser.Result finalResult = result;
            final Throwable error = lastError;
            AndroidUtilities.runOnUIThread(() -> {
                if (onLoadingEnd != null) onLoadingEnd.run();
                if (cancelled[0]) return;
                TLRPC.TL_webPage webPage = null;
                if (finalResult != null && finalResult.imageCount() > 0) {
                    try {
                        webPage = buildWebPage(url, finalResult);
                    } catch (Throwable e) {
                        FileLog.e("TelegraphInstantView: buildWebPage failed", e);
                    }
                } else if (error != null) {
                    FileLog.e("TelegraphInstantView: no images for " + path + " err=" + error);
                } else {
                    FileLog.e("TelegraphInstantView: no images for " + path);
                }
                boolean opened = false;
                if (webPage != null) {
                    opened = openArticleViewer(currentAccount, webPage, url);
                }
                if (!opened && fallback != null) {
                    fallback.run();
                }
            });
        });
        return () -> cancelled[0] = true;
    }

    private static boolean openArticleViewer(int currentAccount, TLRPC.TL_webPage webPage, String url) {
        try {
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment != null) {
                boolean ok = fragment.createArticleViewer(false).open(webPage, url);
                if (ok) return true;
                FileLog.e("TelegraphInstantView: ArticleViewer.open returned false");
            }
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.openArticle, webPage, url);
            return true;
        } catch (Throwable e) {
            FileLog.e("TelegraphInstantView: openArticleViewer failed", e);
            return false;
        }
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
                // Stable placeholder aspect (3:4) so the list lays out without downloading.
                // Do NOT discover real size via requestLayout — that cascades loads for every image.
                photo.w = 3;
                photo.h = 4;
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
        // Stable negative id from url so BottomSheetTabs can match on reopen without colliding with server ids
        webPage.id = Long.MIN_VALUE + 1 + (url.hashCode() & 0xffffffffL);
        webPage.url = url;
        webPage.display_url = url.replaceFirst("^https?://", "");
        webPage.type = "article";
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

    private static String httpGetApi(String path) throws Exception {
        // Prefer java.net.URI so unicode paths are encoded exactly once (avoids Uri.encode quirks).
        Exception last = null;
        final String[] urls = new String[]{
                new URI("https", "api.telegra.ph", "/getPage/" + path, "return_content=true", null).toASCIIString(),
                "https://api.telegra.ph/getPage/" + URLEncoder.encode(path, "UTF-8").replace("+", "%20") + "?return_content=true"
        };
        for (String url : urls) {
            try {
                return httpGet(url);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("api fetch failed");
    }

    private static String httpGetHtml(String path) throws Exception {
        Exception last = null;
        final String[] urls = new String[]{
                new URI("https", "telegra.ph", "/" + path, null, null).toASCIIString(),
                "https://telegra.ph/" + URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        };
        for (String url : urls) {
            try {
                return httpGet(url);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("html fetch failed");
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = null;
        try {
            final URL url = new URL(urlString);
            connection = openConnection(url);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            connection.setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
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

    /** Use Telegram's SOCKS proxy when configured (MTProto secret proxies cannot tunnel plain HTTP). */
    private static HttpURLConnection openConnection(URL url) throws Exception {
        if (SharedConfig.isProxyEnabled() && SharedConfig.currentProxy != null
                && TextUtils.isEmpty(SharedConfig.currentProxy.secret)
                && !TextUtils.isEmpty(SharedConfig.currentProxy.address)
                && SharedConfig.currentProxy.port > 0) {
            final Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(SharedConfig.currentProxy.address, SharedConfig.currentProxy.port));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }
}
