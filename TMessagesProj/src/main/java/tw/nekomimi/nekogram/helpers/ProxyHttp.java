package tw.nekomimi.nekogram.helpers;

import org.telegram.messenger.ProxyConnection;

import java.net.HttpURLConnection;
import java.net.URL;

/** @see ProxyConnection */
public final class ProxyHttp {
    private ProxyHttp() {}
    public static HttpURLConnection open(URL url) throws Exception {
        return ProxyConnection.open(url);
    }
    public static boolean canUseSocksProxy() {
        return ProxyConnection.canUseSocksProxy();
    }
}
