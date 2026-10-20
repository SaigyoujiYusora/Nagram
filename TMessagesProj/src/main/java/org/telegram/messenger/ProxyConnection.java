package org.telegram.messenger;

import android.text.TextUtils;

import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;

/**
 * HTTP connections that honor Telegram SOCKS5 proxy (not MTProto {@code secret} proxies).
 */
public final class ProxyConnection {

    private static final Object AUTH_LOCK = new Object();
    private static volatile boolean authenticatorInstalled;

    private ProxyConnection() {
    }

    public static boolean canUseSocksProxy() {
        return SharedConfig.isProxyEnabled()
                && SharedConfig.currentProxy != null
                && TextUtils.isEmpty(SharedConfig.currentProxy.secret)
                && !TextUtils.isEmpty(SharedConfig.currentProxy.address)
                && SharedConfig.currentProxy.port > 0;
    }

    public static HttpURLConnection open(URL url) throws Exception {
        if (canUseSocksProxy()) {
            final SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            ensureSocksAuthenticator(info);
            final Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(info.address, info.port));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static void ensureSocksAuthenticator(SharedConfig.ProxyInfo info) {
        if (info == null || TextUtils.isEmpty(info.username)) {
            return;
        }
        synchronized (AUTH_LOCK) {
            if (authenticatorInstalled) {
                return;
            }
            final String user = info.username;
            final char[] pass = info.password != null ? info.password.toCharArray() : new char[0];
            try {
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        final String proto = getRequestingProtocol();
                        if (proto != null && proto.toLowerCase().contains("socks")) {
                            return new PasswordAuthentication(user, pass);
                        }
                        return null;
                    }
                });
                authenticatorInstalled = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}
