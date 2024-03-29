package zipkin2.module.storage.lucene;

import io.micrometer.core.instrument.util.StringUtils;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.grouping.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.mapdb.*;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin2.*;
import zipkin2.storage.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.apache.lucene.document.Field.Store.NO;
import static org.apache.lucene.search.BooleanClause.Occur.MUST;

public class LuceneStorage extends StorageComponent {
  private Logger logger = LoggerFactory.getLogger(LuceneStorage.class);

  /**
   * To not produce unnecessarily long queries, we don't look back further than first Lucene support
   */
  static final long EARLIEST_MS = 1576276500000L; // December 2019

  private static final String TAG_PREFIX = "tt";

  private final IndexWriter indexWriter;

  private final List<String> autocompleteKeys;
  private final int autocompleteTtl;
  private final int autocompleteCardinality;

  private final LuceneSpanConsumer spanConsumer;
  private final LuceneSpanStore spanStore;

  private final DB db;
  private final HTreeMap<String, Roaring64NavigableMap> traceIdsByServiceName;
  private final HTreeMap<String, Roaring64NavigableMap> traceIdsByRemoteServiceName;
  private final HTreeMap<String, Roaring64NavigableMap> spanIdsByTraceId;
  private final HTreeMap<UniqueSpanId, Span> spanBySpanId;

  private LuceneStorage(List<String> autocompleteKeys, int autocompleteTtl, int autocompleteCardinality, File indexDirectory) {
    this.autocompleteKeys = autocompleteKeys;
    this.autocompleteTtl = autocompleteTtl;
    this.autocompleteCardinality = autocompleteCardinality;

    try {
      Directory fileIndex = FSDirectory.open(new File(indexDirectory, "lucene").toPath());
      StandardAnalyzer analyzer = new StandardAnalyzer();
      IndexWriterConfig indexWriterConfig = new IndexWriterConfig(analyzer);
      this.indexWriter = new IndexWriter(fileIndex, indexWriterConfig);

      this.spanConsumer = new LuceneSpanConsumer();
      this.spanStore = new LuceneSpanStore();
    } catch (IOException e) {
      throw new RuntimeException("Unable to configure Lucene storage module", e);
    }

    this.db = DBMaker
      .fileDB(new File(indexDirectory, "map.db"))
      .closeOnJvmShutdown()
      .fileMmapEnableIfSupported()
      .make();

    this.spanIdsByTraceId = searchByCriteriaMap("traceId");
    this.traceIdsByServiceName = searchByCriteriaMap("serviceName");
    this.traceIdsByRemoteServiceName = searchByCriteriaMap("remoteServiceName");
    this.spanBySpanId = db.hashMap("spanId")
      .keySerializer(UniqueSpanId.SERIALIZER)
      .valueSerializer(new SpanSerializer())
      .createOrOpen();
  }

  public static Builder newBuilder(File indexDirectory) {
    return new Builder(indexDirectory);
  }

  private HTreeMap<String, Roaring64NavigableMap> searchByCriteriaMap(String criteria) {
    return db
      .hashMap(criteria)
      .keySerializer(Serializer.STRING)
      .valueSerializer(Roaring64NavigableMapSerializer.INSTANCE)
      .createOrOpen();
  }

  @Override
  public void close() throws IOException {
    this.indexWriter.close();
  }

  @Override
  public SpanStore spanStore() {
    return spanStore;
  }

  @Override
  public SpanConsumer spanConsumer() {
    return spanConsumer;
  }

  public static final class Builder extends StorageComponent.Builder {
    private final File indexDirectory;
    private List<String> autocompleteKeys = Collections.emptyList();
    private int autocompleteTtl = (int) TimeUnit.HOURS.toMillis(1);

    /**
     * 5 site tags with cardinality 4000 each.
     */
    private int autocompleteCardinality = 5 * 4000;

    public Builder(File indexDirectory) {
      this.indexDirectory = indexDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Builder strictTraceId(boolean strictTraceId) {
      if (!strictTraceId) {
        throw new UnsupportedOperationException("strictTraceId cannot be disabled");
      }
      return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Builder searchEnabled(boolean searchEnabled) {
      if (!searchEnabled) {
        throw new UnsupportedOperationException("searchEnabled cannot be disabled");
      }
      return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Builder autocompleteKeys(List<String> autocompleteKeys) {
      this.autocompleteKeys = autocompleteKeys;
      return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Builder autocompleteTtl(int autocompleteTtl) {
      this.autocompleteTtl = autocompleteTtl;
      return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Builder autocompleteCardinality(int autocompleteCardinality) {
      this.autocompleteCardinality = autocompleteCardinality;
      return this;
    }

    @Override
    public LuceneStorage build() {
      return new LuceneStorage(autocompleteKeys, autocompleteTtl, autocompleteCardinality, indexDirectory);
    }
  }

  class LuceneSpanStore implements Traces, SpanStore, ServiceAndSpanNames {
    @Override
    public Call<List<String>> getRemoteServiceNames(String serviceName) {
      return Call.create(traceIdsByRemoteServiceName.getKeys().stream().sorted().collect(toList()));
    }

    @Override
    public Call<List<Span>> getTrace(String traceId) {
      return Call.create(getTraceBlocking(traceId));
    }

    @Override
    public Call<List<List<Span>>> getTraces(Iterable<String> traceIds) {
      return Call.create(stream(traceIds.spliterator(), false)
        .map(this::getTraceBlocking)
        .collect(toList()));
    }

    private List<Span> getTraceBlocking(String traceId) {
      String normalizedTraceId = Span.normalizeTraceId(traceId);
      Roaring64NavigableMap bitmap = spanIdsByTraceId.get(normalizedTraceId);
      if (bitmap == null)
        return emptyList();

      List<Span> spans = new ArrayList<>();
      bitmap.forEach(spanId -> {
        String normalizedSpanId = Span.normalizeTraceId(Long.toHexString(spanId));
        spans.add(spanBySpanId.get(new UniqueSpanId(normalizedTraceId, normalizedSpanId)));
      });

      return spans;
    }

    @Override
    public Call<List<String>> getServiceNames() {
      return Call.create(traceIdsByServiceName.keySet().stream().sorted().collect(toList()));
    }

    @Override
    public Call<List<String>> getSpanNames(String serviceName) {
      try (IndexReader reader = DirectoryReader.open(indexWriter)) {
        TermGroupSelector spanNameGroupSelector = new TermGroupSelector("spanName");
        FirstPassGroupingCollector<BytesRef> firstPassCollector = new FirstPassGroupingCollector<>(spanNameGroupSelector,
          Sort.RELEVANCE, 10000);

        new IndexSearcher(reader).search(new TermQuery(new Term("serviceName", serviceName)), firstPassCollector);

        Collection<SearchGroup<BytesRef>> topGroups = firstPassCollector.getTopGroups(0);
        if (topGroups == null) {
          return Call.emptyList();
        }

        return Call.create(new DistinctValuesCollector<>(spanNameGroupSelector, topGroups, spanNameGroupSelector)
          .getGroups()
          .stream()
          .map(group -> group.groupValue.utf8ToString())
          .collect(toList()));
      } catch (IOException e) {
        logger.error("Failed to retrieve span names", e);
        return Call.emptyList();
      }
    }

    @Override
    public Call<List<DependencyLink>> getDependencies(long endTs, long lookback) {
      if (endTs <= 0) throw new IllegalArgumentException("endTs <= 0");
      if (lookback <= 0) throw new IllegalArgumentException("lookback <= 0");

      throw new UnsupportedOperationException("Lucene storage does not support dependency links yet");
    }

    @Override
    public Call<List<List<Span>>> getTraces(QueryRequest request) {
      try (IndexReader reader = DirectoryReader.open(indexWriter)) {
        IndexSearcher indexSearcher = new IndexSearcher(reader);
        List<List<Span>> results = new ArrayList<>();
        ScoreDoc scoreDoc = null;
        while (results.size() <= request.limit()) {
          ScoreDoc prevScoreDoc = scoreDoc;
          scoreDoc = tracesPage(request, indexSearcher, results, scoreDoc);
          if (scoreDoc == null || (prevScoreDoc != null && prevScoreDoc.doc == scoreDoc.doc)) {
            break; // no more results available
          }
        }
        return Call.create(results.size() > request.limit() ? results.subList(0, request.limit()) : results);
      } catch (IOException e) {
        logger.error("Failed to get traces", e);
        return Call.emptyList();
      }
    }

    private ScoreDoc tracesPage(QueryRequest request, IndexSearcher indexSearcher, List<List<Span>> results,
                                ScoreDoc previous) throws IOException {
      BooleanQuery.Builder query = new BooleanQuery.Builder();

      query.add(LongPoint.newRangeQuery("timestampPoint", 0, request.endTs()), MUST);

      if (request.remoteServiceName() != null) {
        query.add(new TermQuery(new Term("remoteServiceName", request.remoteServiceName())), MUST);
      }

      if (request.serviceName() != null) {
        query.add(new TermQuery(new Term("serviceName", request.serviceName())), MUST);
      }

      if (request.spanName() != null) {
        query.add(new TermQuery(new Term("spanName", request.spanName())), MUST);
      }

      String[] tagOnlyQueries = request.annotationQuery().entrySet().stream()
        .filter(annotation -> StringUtils.isBlank(annotation.getValue()))
        .map(Map.Entry::getKey)
        .map(LuceneStorage::normalizeValue)
        .sorted()
        .toArray(String[]::new);

      if (tagOnlyQueries.length > 0) {
        query.add(new PhraseQuery(Integer.MAX_VALUE, "annotationsAndTagKeys", tagOnlyQueries), MUST);
      }

      request.annotationQuery().entrySet().stream()
        .filter(annotation -> StringUtils.isNotBlank(annotation.getValue()))
        .forEach(annotation -> {
          String field = TAG_PREFIX + annotation.getKey();
          query.add(new TermQuery(new Term(field, normalizeValue(annotation.getValue()))), MUST);
        });

      // request.limit() is meant to limit trace ids, but this is effectively limiting unique SPAN ids, which is
      // why we have to potentially select multiple pages of traces until we get as many as we need.
      // could do more to progressively refine this limit as we see how many traces each page approximately selects
      TopDocs search = previous == null ? indexSearcher.search(query.build(), request.limit()) :
        indexSearcher.searchAfter(previous, query.build(), request.limit());

      Set<String> traceIds = new HashSet<>();

      for (ScoreDoc scoreDoc : search.scoreDocs) {
        Document doc = indexSearcher.doc(scoreDoc.doc);
        traceIds.add(Long.toHexString(doc.getField("traceId").numericValue().longValue()));
      }

      traceIds.stream().map(this::getTraceBlocking)
        .filter(trace -> trace.stream().allMatch(span -> span.timestampAsLong() >= request.endTs() - request.lookback()))
        .filter(trace -> {
          Long minDuration = request.minDuration();
          Long maxDuration = request.maxDuration();
          return (minDuration == null && maxDuration == null) || trace.stream()
            .anyMatch(span -> span.durationAsLong() <= (maxDuration == null ? Long.MAX_VALUE : maxDuration) &&
              span.durationAsLong() >= (minDuration == null ? Long.MIN_VALUE : minDuration));
        })
        .forEach(results::add);

      return search.scoreDocs.length == 0 ? null : search.scoreDocs[search.scoreDocs.length - 1];
    }
  }

  class LuceneSpanConsumer implements SpanConsumer {
    @Override
    public Call<Void> accept(List<Span> spans) {
      if (spans.isEmpty()) {
        return Call.create(null);
      }

      try {
        for (Span span : spans) {
          addToBitmap(traceIdsByServiceName, span.localServiceName(), span.traceId());
          addToBitmap(traceIdsByRemoteServiceName, span.remoteServiceName(), span.traceId());
          addToBitmap(spanIdsByTraceId, span.traceId(), span.id());
          spanBySpanId.put(new UniqueSpanId(span.traceId(), span.id()), span);

          Document document = new Document();

          document.add(new StoredField("traceId", Long.valueOf(span.traceId(), 16)));
          document.add(new SortedDocValuesField("spanName", new BytesRef(span.name())));
          document.add(new TextField("spanName", span.name(), NO));
          document.add(new TextField("serviceName", span.localServiceName(), NO));
          document.add(new TextField("remoteServiceName", span.remoteServiceName(), NO));
          document.add(new LongPoint("timestampPoint", span.timestamp()));
          document.add(new LongPoint("durationPoint", span.duration()));
          document.add(new TextField("annotationsAndTagKeys",
              Stream.concat(
                span.annotations().stream().map(Annotation::value),
                span.tags().keySet().stream()
              ).collect(joining(" ")),
              NO
            )
          );

          span.tags().entrySet().stream()
            .filter(tag -> StringUtils.isNotBlank(tag.getValue()))
            .forEach(tag -> document.add(new TextField(TAG_PREFIX + tag.getKey(), tag.getValue(), NO)));

          indexWriter.addDocument(document);
        }
        indexWriter.commit();
      } catch (IOException e) {
        logger.error("Failed to store trace", e);
      }

      return Call.create(null);
    }

    private void addToBitmap(HTreeMap<String, Roaring64NavigableMap> map, String key, String value) {
      Roaring64NavigableMap bitmap = map.get(key);
      if (bitmap == null) {
        bitmap = new Roaring64NavigableMap();
      }
      bitmap.add(Long.valueOf(value, 16));
      map.put(key, bitmap);
    }
  }

  private static StandardAnalyzer standardAnalyzer = new StandardAnalyzer();

  static String normalizeValue(String value) {
    try (TokenStream tokenStream = standardAnalyzer.tokenStream("", value)) {
      tokenStream.reset();

      CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);

      StringBuilder normalized = new StringBuilder();
      int token = 0;
      while (tokenStream.incrementToken()) {
        if (token++ > 0)
          normalized.append(' ');
        normalized.append(charTermAttribute.toString());
      }

      return normalized.toString();
    } catch (IOException e) {
      throw new RuntimeException("Unable to tokenize search term " + value, e);
    }
  }

  static class LuceneAutocompleteTags implements AutocompleteTags {
    @Override
    public Call<List<String>> getKeys() {
      // FIXME implement me!
      return null;
    }

    @Override
    public Call<List<String>> getValues(String key) {
      // FIXME implement me!
      return null;
    }
  }

  @Override
  public AutocompleteTags autocompleteTags() {
    return new LuceneAutocompleteTags();
  }

  @Override
  public ServiceAndSpanNames serviceAndSpanNames() {
    return spanStore;
  }
}
