package net.byebye.pregenBar;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code &} colour codes plus {@code &#RRGGBB} / {@code <#RRGGBB>} hex colours. */
public final class Text {

    private static final Pattern HEX = Pattern.compile("(?:&#|<#)([A-Fa-f0-9]{6})>?");

    private Text() {}

    public static String colorize(String message) {
        if (message == null || message.isEmpty()) return message == null ? "" : message;
        Matcher m = HEX.matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(ChatColor.of("#" + m.group(1)).toString()));
        }
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }
}
