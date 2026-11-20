package com.glasskit.app;

import android.content.pm.ApplicationInfo;

import java.util.Locale;

/** Kategori app untuk folder automatik dan App Library (ikut nama pakej + kategori Play). */
final class AppCats {
    private AppCats() {}

    static final String SOCIAL = "social", FINANCE = "finance", AI = "ai", SHOP = "shop", FUN = "fun", GAMES = "games",
            CREATIVE = "creative", WORK = "work", UTIL = "util", GOOGLE = "google", OTHER = "other";

    /** Susunan paparan di App Library. */
    static final String[] ORDER = {SOCIAL, AI, FINANCE, FUN, SHOP, GAMES, CREATIVE, WORK, GOOGLE, UTIL, OTHER};

    private static boolean has(String s, String... k) {
        for (String x : k) if (s.contains(x)) return true;
        return false;
    }

    static String of(String pkg, String label, ApplicationInfo ai) {
        String p = (pkg + " " + label).toLowerCase(Locale.ROOT);
        if (has(p, "openai", "chatgpt", "anthropic", "claude", "gemini", "bard", "copilot", "perplexity", "deepseek", "grok",
                "poe.", "character.ai", "characterai", "meta.ai", "mistral", "kimi", "qwen")) return AI;
        if (has(p, "bank", "maybank", "cimb", "rhb", "bsn", "ambank", "hlb", "hongleong", "publicbank", "pbe", "touchngo", "tngdigital",
                "boost", "shopeepay", "paypal", "wallet", "finance", "duit", "maeapp", "bigpay", "affin", "alliance", "agrobank",
                "muamalat", "rakyat", "ocbc", "uob", "hsbc", "kwsp", "epf", "tabung", "asnb", "lhdn", "binance", "luno", "crypto",
                "stashaway", "moomoo", "rakuten", "gxbank", "aeonbank", "setel", "myeg")) return FINANCE;
        if (has(p, "whatsapp", "facebook", "instagram", "messenger", "telegram", "twitter", "com.x.", "tiktok", "musically",
                "zhiliaoapp", "snapchat", "discord", "barcelona", "threads", "linkedin", "reddit", "wechat", "thoughtcrime.securesms", "jp.naver.line",
                "pinterest", "tumblr", "bluesky", "imo.", "viber", "kakao")) return SOCIAL;
        if (has(p, "shopee", "lazada", "temu", "amazon", "aliexpress", "zalora", "shein", "mudah", "ikea", "carousell",
                "foodpanda", "grabfood", "grab.", "mcdonald", "kfc", "zus", "tealive", "starbucks")) return SHOP;
        if (ai != null && ai.category == ApplicationInfo.CATEGORY_GAME) return GAMES;
        if (has(p, "game", "games", "roblox", "pubg", "mobile.legends", "mobilelegends", "freefire", "genshin", "supercell")) return GAMES;
        if (has(p, "youtube", "spotify", "netflix", "music", "video", "player", ".tv", "primevideo", "disney", "viu", "iqiyi",
                "bilibili", "anime", "tune", "podcast", "joox", "tonton", "astro", "soundcloud", "deezer", "wetv")) return FUN;
        if (has(p, "photos", "gallery", "capcut", "canva", "lightroom", "snapseed", "picsart", "editor", "vsco", "camera",
                "inshot", "remini", "pixellab")) return CREATIVE;
        if (has(p, "docs", "sheets", "slides", "office", "word", "excel", "powerpoint", "teams", "zoom", "meet", "notion",
                "keep", "drive", "camscanner", "pdf", "outlook", "gmail", "mail", "calendar", "slack", "trello", "evernote", "onenote"))
            return WORK;
        if (ai != null) {
            switch (ai.category) {
                case ApplicationInfo.CATEGORY_SOCIAL: return SOCIAL;
                case ApplicationInfo.CATEGORY_AUDIO:
                case ApplicationInfo.CATEGORY_VIDEO: return FUN;
                case ApplicationInfo.CATEGORY_IMAGE: return CREATIVE;
                case ApplicationInfo.CATEGORY_PRODUCTIVITY: return WORK;
                case ApplicationInfo.CATEGORY_NEWS:
                case ApplicationInfo.CATEGORY_MAPS: return OTHER;
                default: break;
            }
        }
        if (has(p, "settings", "calculator", "clock", "files", "contacts", "tool", "manager", "cleaner", "zarchiver", "shizuku",
                "ashell", "termux", "vpn", "scanner", "launcher", "recorder", "weather", "compass", "flashlight", "equalizer",
                "bitdefender", "antivirus", "devcheck", "glasskit")) return UTIL;
        if (pkg.startsWith("com.google.") || pkg.startsWith("com.android.")) return GOOGLE;
        return OTHER;
    }

    static int name(String cat) {
        switch (cat) {
            case SOCIAL: return R.string.cat_social;
            case FINANCE: return R.string.cat_finance;
            case AI: return R.string.cat_ai;
            case SHOP: return R.string.cat_shop;
            case FUN: return R.string.cat_fun;
            case GAMES: return R.string.cat_games;
            case CREATIVE: return R.string.cat_creative;
            case WORK: return R.string.cat_work;
            case UTIL: return R.string.cat_util;
            case GOOGLE: return R.string.cat_google;
            default: return R.string.cat_other;
        }
    }
}
