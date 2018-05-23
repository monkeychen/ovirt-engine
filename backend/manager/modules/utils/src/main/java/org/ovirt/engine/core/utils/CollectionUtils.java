package org.ovirt.engine.core.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ovirt.engine.core.common.utils.Pair;

public class CollectionUtils {
    public static <U, V> Map<U, List<V>> pairsToMap(final List<Pair<U, V>> pairList) {
        Map<U, List<V>> res = new HashMap<>();
        for (Pair<U, V> pair: pairList) {
            res.putIfAbsent(pair.getFirst(), new ArrayList<>());
            res.get(pair.getFirst()).add(pair.getSecond());
        }
        return res;
    }

    public static <T> List<T> nullToEmptyList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    public static <T> List<T> emptyListToNull(List<T> list) {
        return list != null && list.isEmpty() ? null : list;
    }

}
