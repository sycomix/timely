package timely.store;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.ClientConfiguration;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import timely.api.model.Meta;
import timely.configuration.Accumulo;
import timely.configuration.Configuration;

public class MetaCacheImpl implements MetaCache {

    private static Logger LOG = LoggerFactory.getLogger(MetaCacheImpl.class);
    private static final Object DUMMY = new Object();
    private volatile boolean closed = false;
    private Cache<Meta, Object> cache = null;
    private Configuration configuration;
    private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

    @Override
    public void init(Configuration config) {
        configuration = config;
        cache = Caffeine.newBuilder().initialCapacity(1000).build();
        long cacheRefreshMinutes = configuration.getMetaCache().getCacheRefreshMinutes();
        if (cacheRefreshMinutes > 0) {
            executorService.scheduleAtFixedRate(() -> refreshCache(config.getMetaCache().getExpirationMinutes()), 1,
                    cacheRefreshMinutes, TimeUnit.MINUTES);
        }
    }

    private void refreshCache(long expirationMinutes) {
        long oldestTimestamp = System.currentTimeMillis() - (expirationMinutes * 60 * 1000);
        Map<String, String> properties = new HashMap<>();
        Accumulo accumuloConf = configuration.getAccumulo();
        properties.put("instance.name", accumuloConf.getInstanceName());
        properties.put("instance.zookeeper.host", accumuloConf.getZookeepers());
        final ClientConfiguration aConf = ClientConfiguration.fromMap(properties);
        String metaTable = configuration.getMetaTable();
        Map<String, Map<String, Long>> metricMap = new HashMap<>();
        Scanner scanner = null;
        try {
            Map<Meta, Object> newCache = new HashMap<>();
            final Instance instance = new ZooKeeperInstance(aConf);
            Connector connector = instance.getConnector(accumuloConf.getUsername(),
                    new PasswordToken(accumuloConf.getPassword()));
            scanner = connector.createScanner(metaTable, Authorizations.EMPTY);
            LOG.debug("Begin scanning " + metaTable);
            Key metricPrefixBeginKey = new Key(Meta.METRIC_PREFIX);
            int firstChar = Meta.METRIC_PREFIX.charAt(0);
            Key metricPrefixEndKey = new Key(String.valueOf((char) (firstChar + 1)) + ':');
            Range metricRange = new Range(metricPrefixBeginKey, true, metricPrefixEndKey, false);
            scanner.setRange(metricRange);
            Set<String> allMetrics = new TreeSet<>();
            for (Map.Entry<Key, Value> entry : scanner) {
                Meta meta = Meta.parse(entry.getKey(), entry.getValue(), Meta.METRIC_PREFIX);
                allMetrics.add(meta.getMetric());
            }
            for (String currMetric : allMetrics) {
                Key begin = new Key(Meta.VALUE_PREFIX + currMetric);
                Key end = new Key(Meta.VALUE_PREFIX + currMetric + '\0');
                Range range = new Range(begin, true, end, false);
                scanner.setRange(range);
                Iterator<Map.Entry<Key, Value>> itr = scanner.iterator();
                boolean maxedOutValues = false;
                while (itr.hasNext() && !maxedOutValues) {
                    Map.Entry<Key, Value> entry = itr.next();
                    Meta meta = Meta.parse(entry.getKey(), entry.getValue(), Meta.VALUE_PREFIX);
                    String metric = meta.getMetric();
                    String tagKey = meta.getTagKey();
                    Map<String, Long> tagMap = metricMap.get(metric);
                    if (tagMap == null) {
                        tagMap = new HashMap<>();
                        metricMap.put(metric, tagMap);
                    }
                    long numTagValues = tagMap.getOrDefault(tagKey, 0l);
                    if (entry.getKey().getTimestamp() > oldestTimestamp) {
                        tagMap.put(tagKey, ++numTagValues);
                        if (numTagValues <= configuration.getMetaCache().getMaxTagValues()) {
                            newCache.put(meta, DUMMY);
                        } else {
                            // found maxTagValues on this refresh
                            maxedOutValues = true;
                        }
                    }
                }
            }
            synchronized (cache) {
                cache.invalidateAll();
                cache.putAll(newCache);
            }
            LOG.debug("Finished scanning " + metaTable);
        } catch (AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
            LOG.error(e.getMessage(), e);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
    }

    @Override
    public void add(Meta meta) {
        cache.put(meta, DUMMY);
    }

    @Override
    public boolean contains(Meta meta) {
        return cache.asMap().containsKey(meta);
    }

    @Override
    public void addAll(Collection<Meta> c) {
        c.forEach(m -> cache.put(m, DUMMY));
    }

    @Override
    public Iterator<Meta> iterator() {
        return cache.asMap().keySet().iterator();
    }

    @Override
    public void close() {
        this.closed = true;
        this.executorService.shutdown();
        try {
            this.executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {

        } finally {
            if (!this.executorService.isTerminated()) {
                this.executorService.shutdownNow();
            }
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
