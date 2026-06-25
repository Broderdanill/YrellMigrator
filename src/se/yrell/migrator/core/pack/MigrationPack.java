package se.yrell.migrator.core.pack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Local, exportable migration package with object-definition and form-data scopes. */
public final class MigrationPack {
    private String name;
    private long createdAtMillis = System.currentTimeMillis();
    private long modifiedAtMillis = createdAtMillis;
    private final List<MigrationPackItem> items = new ArrayList<MigrationPackItem>();

    public MigrationPack() {
        this.name = "Migration Pack " + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(createdAtMillis));
    }

    public String getName() { return name == null || name.trim().length() == 0 ? "Migration Pack" : name; }
    public void setName(String name) { this.name = name == null ? "" : name.trim(); touch(); }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis <= 0 ? System.currentTimeMillis() : createdAtMillis; }
    public long getModifiedAtMillis() { return modifiedAtMillis; }
    public void setModifiedAtMillis(long modifiedAtMillis) { this.modifiedAtMillis = modifiedAtMillis <= 0 ? System.currentTimeMillis() : modifiedAtMillis; }

    public List<MigrationPackItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public int size() { return items.size(); }
    public boolean isEmpty() { return items.isEmpty(); }

    public int getDefinitionCount() {
        int count = 0;
        for (MigrationPackItem item : items) {
            if (item != null && item.isDefinition()) count++;
        }
        return count;
    }

    public int getFormDataCount() {
        int count = 0;
        for (MigrationPackItem item : items) {
            if (item != null && item.isFormData()) count++;
        }
        return count;
    }

    public boolean add(MigrationPackItem item) {
        if (item == null) {
            return false;
        }
        String key = item.stableKey();
        for (MigrationPackItem existing : items) {
            if (existing != null && existing.stableKey().equals(key)) {
                return false;
            }
        }
        items.add(item);
        touch();
        return true;
    }

    public void addAll(List<MigrationPackItem> newItems) {
        if (newItems == null) return;
        for (MigrationPackItem item : newItems) {
            add(item);
        }
    }

    public void remove(MigrationPackItem item) {
        if (items.remove(item)) {
            touch();
        }
    }

    public void clear() {
        if (!items.isEmpty()) {
            items.clear();
            touch();
        }
    }

    public List<MigrationPackItem> orderedForRun(List<MigrationPackItem> sourceItems) {
        final List<MigrationPackItem> copy = new ArrayList<MigrationPackItem>();
        if (sourceItems != null) {
            for (MigrationPackItem item : sourceItems) {
                if (item != null) copy.add(item);
            }
        }
        final Map<MigrationPackItem, Integer> originalIndex = new IdentityHashMap<MigrationPackItem, Integer>();
        for (int i = 0; i < copy.size(); i++) {
            originalIndex.put(copy.get(i), Integer.valueOf(i));
        }
        Collections.sort(copy, new Comparator<MigrationPackItem>() {
            public int compare(MigrationPackItem a, MigrationPackItem b) {
                int pa = a == null ? 999 : a.executionPhase();
                int pb = b == null ? 999 : b.executionPhase();
                if (pa != pb) return pa < pb ? -1 : 1;
                Integer ia = originalIndex.get(a);
                Integer ib = originalIndex.get(b);
                int ai = ia == null ? 0 : ia.intValue();
                int bi = ib == null ? 0 : ib.intValue();
                return ai == bi ? 0 : (ai < bi ? -1 : 1);
            }
        });
        return copy;
    }

    public void sortForRun() {
        List<MigrationPackItem> ordered = orderedForRun(items);
        items.clear();
        items.addAll(ordered);
        touch();
    }

    public void moveUp(List<MigrationPackItem> selected) {
        Set<MigrationPackItem> set = new LinkedHashSet<MigrationPackItem>();
        if (selected != null) set.addAll(selected);
        if (set.isEmpty()) return;
        for (int i = 1; i < items.size(); i++) {
            MigrationPackItem current = items.get(i);
            MigrationPackItem previous = items.get(i - 1);
            if (set.contains(current) && !set.contains(previous)) {
                items.set(i - 1, current);
                items.set(i, previous);
            }
        }
        touch();
    }

    public void moveDown(List<MigrationPackItem> selected) {
        Set<MigrationPackItem> set = new LinkedHashSet<MigrationPackItem>();
        if (selected != null) set.addAll(selected);
        if (set.isEmpty()) return;
        for (int i = items.size() - 2; i >= 0; i--) {
            MigrationPackItem current = items.get(i);
            MigrationPackItem next = items.get(i + 1);
            if (set.contains(current) && !set.contains(next)) {
                items.set(i + 1, current);
                items.set(i, next);
            }
        }
        touch();
    }

    public void clearLastRun(List<MigrationPackItem> selected) {
        List<MigrationPackItem> target = selected == null || selected.isEmpty() ? items : selected;
        for (MigrationPackItem item : target) {
            if (item != null) {
                item.setLastRunOutcome("");
                item.setLastRunMessage("");
                item.setLastRunAtMillis(0L);
            }
        }
        touch();
    }

    public String environmentSummary() {
        Set<String> pairs = new LinkedHashSet<String>();
        for (MigrationPackItem item : items) {
            if (item == null) continue;
            String source = item.getSourceServer() == null ? "" : item.getSourceServer();
            String target = item.getTargetServer() == null ? "" : item.getTargetServer();
            if (source.length() > 0 || target.length() > 0) {
                pairs.add(source + " → " + target);
            }
        }
        if (pairs.isEmpty()) return "No environment bound yet";
        if (pairs.size() == 1) return pairs.iterator().next();
        return pairs.size() + " environment pair(s)";
    }

    public void touch() {
        modifiedAtMillis = System.currentTimeMillis();
    }
}
