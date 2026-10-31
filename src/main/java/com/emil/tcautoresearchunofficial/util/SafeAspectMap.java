package com.emil.tcautoresearchunofficial.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import thaumcraft.api.aspects.Aspect;

/**
 * 供 {@link thaumcraft.api.aspects.AspectList} 使用的「遍历安全」映射。
 *
 * <p>神秘时代的 {@code AspectList.aspects} 是普通 {@link LinkedHashMap}：渲染线程在
 * {@code GuiResearchTable.drawAspectText} 里遍历它，而 {@code PacketAspectDiscovery}
 * 会在 Netty 线程直接往里写（{@code PlayerKnowledge.addDiscoveredAspect}）。
 * 两者相撞就抛 {@code ConcurrentModificationException} —— Hodgepodge 把
 * {@code getAspectsSorted()} 改成 stream 排序后，遍历窗口被放大，崩溃概率大幅上升。</p>
 *
 * <p>这里把所有结构性写操作与遍历读取放到同一把锁上：写走 {@code put/remove/clear}，
 * 读走 {@code keySet/values/entrySet} 返回的快照。遍历快照永远不会被写入打断。</p>
 */
public final class SafeAspectMap extends LinkedHashMap<Aspect, Integer> {

    private static final long serialVersionUID = 1L;

    public SafeAspectMap(Map<Aspect, Integer> initial) {
        super(initial == null ? 16 : Math.max(16, initial.size() * 2));
        if (initial != null) {
            super.putAll(initial);
        }
    }

    @Override
    public synchronized Integer put(Aspect key, Integer value) {
        return super.put(key, value);
    }

    @Override
    public synchronized Integer remove(Object key) {
        return super.remove(key);
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }

    @Override
    public synchronized void putAll(Map<? extends Aspect, ? extends Integer> map) {
        super.putAll(map);
    }

    @Override
    public synchronized Integer get(Object key) {
        return super.get(key);
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        return super.containsKey(key);
    }

    @Override
    public synchronized int size() {
        return super.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return super.isEmpty();
    }

    @Override
    public synchronized Set<Aspect> keySet() {
        return new LinkedHashSet<Aspect>(super.keySet());
    }

    @Override
    public synchronized Collection<Integer> values() {
        return new ArrayList<Integer>(super.values());
    }

    @Override
    public synchronized Set<Map.Entry<Aspect, Integer>> entrySet() {
        return new LinkedHashSet<Map.Entry<Aspect, Integer>>(super.entrySet());
    }
}
