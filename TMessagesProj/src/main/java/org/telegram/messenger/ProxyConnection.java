package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.utils.proxy.ProxySettings;

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
        final SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
        if (!SharedConfig.isProxyEnabled() || info == null || info.settings == null) {
            return false;
        }
        final ProxySettings settings = info.settings;
        return settings.getType() == ProxySettings.Type.SOCKS5
                && TextUtils.isEmpty(settings.getSecret())
                && !TextUtils.isEmpty(settings.getAddress())
                && settings.getPort() > 0;
    }

    public static HttpURLConnection open(URL url) throws Exception {
        if (canUseSocksProxy()) {
            final SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            ensureSocksAuthenticator(info);
            final Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(info.settings.getAddress(), info.settings.getPort()));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private static void ensureSocksAuthenticator(SharedConfig.ProxyInfo info) {
        if (info == null || info.settings == null || TextUtils.isEmpty(info.settings.getUser())) {
            return;
        }
        synchronized (AUTH_LOCK) {
            if (authenticatorInstalled) {
                return;
            }
            final String user = info.settings.getUser();
            final String password = info.settings.getPassword();
            final char[] pass = password != null ? password.toCharArray() : new char[0];
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
