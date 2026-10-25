package dev.velox.core.bench;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Collects stop-the-world GC pause durations while a benchmark runs.
 *
 * <p>Notifications arrive on a JMX service thread, so the render thread pays nothing.
 * Only pause-reporting collectors count: ZGC and Shenandoah expose separate "... Cycles" beans
 * whose durations are mostly concurrent work that does not stop the game, so those are excluded.
 */
public final class GcMonitor implements NotificationListener, AutoCloseable {

    public record Summary(String collector, int pauses, double totalMs, double maxMs, double p99Ms,
                          long droppedEvents) {
        public static Summary empty(String collector) {
            return new Summary(collector, 0, 0, 0, 0, 0);
        }
    }

    private final long[] pauseMicros;
    private int count;
    private long dropped;
    private final List<NotificationEmitter> registered = new ArrayList<>();

    public GcMonitor(int capacity) {
        this.pauseMicros = new long[capacity];
    }

    public static boolean countsPauses(String beanName) {
        return !beanName.contains("Cycles");
    }

    public static String collectorName() {
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            names.add(b.getName());
        }
        return String.join(", ", names);
    }

    public GcMonitor start() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter && countsPauses(bean.getName())) {
                emitter.addNotificationListener(this, null, null);
                registered.add(emitter);
            }
        }
        return this;
    }

    @Override
    public void handleNotification(Notification n, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
        // Concurrent G1 work reports as "G1 Concurrent GC" with cause "No GC"; its duration is
        // the Remark/Cleanup pause, which does stop the world, so it is kept.
        long micros = info.getGcInfo().getDuration() * 1000L; // JMX reports milliseconds
        synchronized (this) {
            if (count < pauseMicros.length) pauseMicros[count++] = micros;
            else dropped++;
        }
    }

    public synchronized Summary summary() {
        String name = collectorName();
        if (count == 0) return new Summary(name, 0, 0, 0, 0, dropped);
        long[] sorted = Arrays.copyOf(pauseMicros, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long v : sorted) total += v;
        return new Summary(name, count, total / 1000.0, sorted[count - 1] / 1000.0,
                FrameStats.percentile(sorted, 0.99) / 1000.0, dropped);
    }

    @Override
    public void close() {
        for (NotificationEmitter e : registered) {
            try {
                e.removeNotificationListener(this);
            } catch (ListenerNotFoundException ignored) {
                // already removed
            }
        }
        registered.clear();
    }

    @Override
    public String toString() {
        Summary s = summary();
        return String.format(Locale.ROOT, "%s: %d pauses, total %.1f ms, max %.1f ms",
                s.collector(), s.pauses(), s.totalMs(), s.maxMs());
    }
}
