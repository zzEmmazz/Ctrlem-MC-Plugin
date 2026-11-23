package com.ctrlem.mcbridge;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

final class UrlSafety {
    private static final String CTRLEM_DOMAIN = "ctrlem.com";
    private static final List<String> BLOCKLIST = List.of("grabify.link", "grabify.ink", "grabify.vip", "iplogger.org", "iplogger.com", "iplogger.ru", "iplogger.info", "iplogger.co", "iplogger.cn", "ipgrabber.ru", "ipgraber.ru", "ip-logging.com", "ip.logging.net", "iplis.ru", "iplo.ru", "ip-stats.net", "ip-tracker.org", "02ip.ru", "2no.co", "yip.su", "ezstat.ru", "iplog.co", "wl.gl", "maper.info", "bmwforum.co", "leancoding.co", "spottyfly.com", "stopify.co", "curiouscat.club", "catsnthings.fun", "webhook.site", "webhook.leak", "hondachat.com", "freebooter.pro", "booter.cafe", "booter.vip", "gyazo.nl", "gyazo-cdn.com", "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly", "adf.ly", "bc.vc", "soo.gd", "s2r.co", "clk.im", "db.tt", "qr.ae", "adfl.co", "short.gy", "rb.gy", "tiny.cc", "t.co", "lnkd.in", "x.co", "scrnch.me", "t.ly", "cutt.ly", "rebrand.ly", "shorturl.at", "shorte.st", "short.cm", "shlink.io", "cshort.org", "tny.sh", "short.io", "hyperurl.co", "linkvertise.com", "link-center.net", "direct-link.net", "exe.app", "l.tc", "soo.link", "ray.app", "clownflare.net", "cloudflaire.com", "cloudfare.com", "cdn-discordapp.com", "dicsord.com", "discord-app.net", "discorcl.com", "dlscord.com", "steamcommunitiy.com", "steanpowered.com", "steampowered-shop.com", "steamcommunlty.com", "nitro-free.net", "nitrodrop.net", "nitrogift.shop", "gift-nitro.com", "fiverr.com");

    private UrlSafety() {
    }

    static String refusal(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = UrlSafety.host(uri);
        if (!scheme.equals("http") && !scheme.equals("https") || host.isEmpty()) {
            return "only http and https links are allowed";
        }
        if (uri.getRawUserInfo() != null) return "links with embedded credentials are not allowed";
        if (UrlSafety.matches(host, CTRLEM_DOMAIN)) {
            return null;
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return host + " is a local address";
        }
        if (BLOCKLIST.stream().anyMatch(blocked -> UrlSafety.matches(host, blocked))) {
            return host + " is on the built-in security blocklist";
        }
        List<String> allowed = Config.INSTANCE.imageAllowedSites;
        if (!allowed.isEmpty() && allowed.stream().noneMatch(site -> UrlSafety.matches(host, site.strip().toLowerCase(Locale.ROOT)))) {
            return host + " is not on the player's allowed sites list";
        }
        return null;
    }

    static String resolvedRefusal(URI uri) {
        String host = UrlSafety.host(uri);
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!UrlSafety.isNonPublic(address)) continue;
                return host + " is a local or private address";
            }
            return null;
        }
        catch (UnknownHostException e) {
            return "couldn't find " + host;
        }
    }

    static String host(URI uri) {
        String host;
        host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean matches(String host, String domain) {
        return !domain.isEmpty() && (host.equals(domain) || host.endsWith("." + domain));
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xFF;
            int a1 = b[1] & 0xFF;
            int a2 = b[2] & 0xFF;
            return a0 == 0 || a0 == 100 && (a1 & 0xC0) == 64 || a0 == 192 && a1 == 0 && a2 == 2 || a0 == 198 && a1 == 51 && a2 == 100 || a0 == 203 && a1 == 0 && a2 == 113 || a0 == 255 && a1 == 255 && (b[2] & b[3] & 0xFF) == 255;
        }
        if (address instanceof Inet6Address) {
            return (b[0] & 0xFE) == 252;
        }
        return false;
    }
}

