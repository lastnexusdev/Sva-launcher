package com.sva.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Category names and channel membership always come from the imported playlist. */
final class LiveTvCatalog {
    static final String ALL = "\u0000all", FAVORITES = "\u0000favorites", RECENT = "\u0000recent";

    static Map<String, List<IptvRepository.Channel>> groups(List<IptvRepository.Channel> channels, Set<String> hidden) {
        Map<String, List<IptvRepository.Channel>> groups = new LinkedHashMap<>();
        for (IptvRepository.Channel channel : channels) {
            if (HiddenChannels.isHidden(hidden, channel)) continue;
            groups.computeIfAbsent(channel.group.trim(), ignored -> new ArrayList<>()).add(channel);
        }
        return groups;
    }

    static List<IptvRepository.Channel> select(List<IptvRepository.Channel> channels, String group,
                                             Set<String> hidden, Set<String> favorites, List<String> recent) {
        List<IptvRepository.Channel> result = new ArrayList<>();
        for (IptvRepository.Channel channel : channels) {
            if (HiddenChannels.isHidden(hidden, channel)) continue;
            if (ALL.equals(group) || FAVORITES.equals(group) && favorites.contains(channel.stableKey())
                    || RECENT.equals(group) && recent.contains(channel.stableKey()) || group.equals(channel.group.trim())) result.add(channel);
        }
        if (RECENT.equals(group)) result.sort(java.util.Comparator.comparingInt(c -> recent.indexOf(c.stableKey())));
        return result;
    }

    static List<String> remember(List<String> previous, String key) {
        List<String> result = new ArrayList<>(); result.add(key);
        for (String old : previous) if (!old.equals(key) && result.size() < 30) result.add(old);
        return result;
    }
}
