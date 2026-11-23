package com.ctrlem.mcbridge;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Which links the mod may download from on a command sender's say-so. Ported from the CtrlEm
 * client's own filter for popupImage and friends (UrlFilter in services/cmds/mod.rs), so a link the
 * client would refuse is refused here too. Keep the blocklist in step with SECURITY_BLOCKLIST there.
 *
 * <p>The mod can't see the player's CtrlEm URL settings, so its own config carries an allowed-sites
 * list instead.
 */
final class UrlSafety {
    private static final String CTRLEM_DOMAIN = "ctrlem.com";

    /** IP grabbers, link shorteners and phishing domains — the CtrlEm client's built-in list. */
    private static final List<String> BLOCKLIST = List.of(
            // IP grabbers / trackers
            "grabify.link", "grabify.ink", "grabify.vip",
            "iplogger.org", "iplogger.com", "iplogger.ru", "iplogger.info", "iplogger.co", "iplogger.cn",
            "ipgrabber.ru", "ipgraber.ru", "ip-logging.com", "ip.logging.net", "iplis.ru", "iplo.ru",
            "ip-stats.net", "ip-tracker.org",
            "02ip.ru", "2no.co", "yip.su", "ezstat.ru", "iplog.co", "wl.gl", "maper.info",
            "bmwforum.co", "leancoding.co", "spottyfly.com", "stopify.co",
            "curiouscat.club", "catsnthings.fun",
            "webhook.site", "webhook.leak",
            "hondachat.com", "freebooter.pro", "booter.cafe", "booter.vip",
            "gyazo.nl", "gyazo-cdn.com",
            // Link shorteners
            "bit.ly", "tinyurl.com", "goo.gl", "ow.ly", "is.gd", "buff.ly", "adf.ly",
            "bc.vc", "soo.gd", "s2r.co", "clk.im", "db.tt", "qr.ae", "adfl.co",
            "short.gy", "rb.gy", "tiny.cc", "t.co", "lnkd.in", "x.co", "scrnch.me",
            "t.ly", "cutt.ly", "rebrand.ly", "shorturl.at",
            "shorte.st", "short.cm", "shlink.io", "cshort.org", "tny.sh", "short.io",
            "hyperurl.co", "linkvertise.com", "link-center.net", "direct-link.net",
            "exe.app", "l.tc", "soo.link",
            // Phishing / high-risk
            "ray.app", "clownflare.net", "cloudflaire.com", "cloudfare.com",
            "cdn-discordapp.com", "dicsord.com", "discord-app.net", "discorcl.com", "dlscord.com",
            "steamcommunitiy.com", "steanpowered.com", "steampowered-shop.com", "steamcommunlty.com",
            "nitro-free.net", "nitrodrop.net", "nitrogift.shop", "gift-nitro.com",
            "fiverr.com");

    private UrlSafety() {}

    /** Why the mod may not load {@code uri}, worded for the sender; {@code null} if it may. */
    static String refusal(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = host(uri);
        if (!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty()) {
            return "only http and https links are allowed";
        }
        // Never blockable, as in the client.
        if (matches(host, CTRLEM_DOMAIN)) return null;
        if (host.equals("localhost") || host.endsWith(".localhost")) return host + " is a local address";
        if (BLOCKLIST.stream().anyMatch(blocked -> matches(host, blocked))) {
            return host + " is on the built-in security blocklist";
        }
        List<? extends String> allowed = Config.IMAGE_ALLOWED_SITES.get();
        if (!allowed.isEmpty() && allowed.stream().noneMatch(site -> matches(host, site.strip().toLowerCase(Locale.ROOT)))) {
            return host + " is not on the player's allowed sites list";
        }
        return null;
    }

    /**
     * Refuses a host that resolves to a local or private address. Goes further than the client,
     * which only checks addresses written literally: a public name pointing at the player's router
     * would otherwise let a sender make their game poke at the home network. Does a DNS lookup, so
     * never call it on the game thread.
     */
    static String resolvedRefusal(URI uri) {
        String host = host(uri);
        if (matches(host, CTRLEM_DOMAIN)) return null;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isNonPublic(address)) return host + " is a local or private address";
            }
            return null;
        } catch (UnknownHostException e) {
            return "couldn't find " + host;
        }
    }

    /** Lowercased, without the trailing dot of a fully-qualified name or IPv6 brackets. */
    static String host(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host;
    }

    private static boolean matches(String host, String domain) {
        return !domain.isEmpty() && (host.equals(domain) || host.endsWith("." + domain));
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xff;
            int a1 = b[1] & 0xff;
            int a2 = b[2] & 0xff;
            return a0 == 0                                           // 0.0.0.0/8
                    || (a0 == 100 && (a1 & 0xc0) == 64)              // 100.64.0.0/10, carrier-grade NAT
                    || (a0 == 192 && a1 == 0 && a2 == 2)             // documentation ranges
                    || (a0 == 198 && a1 == 51 && a2 == 100)
                    || (a0 == 203 && a1 == 0 && a2 == 113)
                    || (a0 == 255 && a1 == 255 && (b[2] & b[3] & 0xff) == 0xff); // broadcast
        }
        if (address instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;                            // fc00::/7, unique local
        }
        return false;
    }
}
