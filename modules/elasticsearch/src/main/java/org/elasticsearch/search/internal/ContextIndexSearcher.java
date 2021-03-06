/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.internal;

import org.apache.lucene.index.ExtendedIndexSearcher;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TimeLimitingCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.Weight;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.lucene.MinimumScoreCollector;
import org.elasticsearch.common.lucene.MultiCollector;
import org.elasticsearch.common.lucene.search.AndFilter;
import org.elasticsearch.common.lucene.search.FilteredCollector;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.search.dfs.CachedDfSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public class ContextIndexSearcher extends ExtendedIndexSearcher {

    public static final class Scopes {
        public static final String MAIN = "_main_";
        public static final String GLOBAL = "_global_";
        public static final String NA = "_na_";
    }

    private final SearchContext searchContext;

    private final IndexReader reader;

    private CachedDfSource dfSource;

    private Map<String, List<Collector>> scopeCollectors;

    private String processingScope;

    public ContextIndexSearcher(SearchContext searchContext, Engine.Searcher searcher) {
        super(searcher.searcher());
        this.searchContext = searchContext;
        this.reader = searcher.searcher().getIndexReader();
    }

    public void dfSource(CachedDfSource dfSource) {
        this.dfSource = dfSource;
    }

    public void addCollector(String scope, Collector collector) {
        if (scopeCollectors == null) {
            scopeCollectors = Maps.newHashMap();
        }
        List<Collector> collectors = scopeCollectors.get(scope);
        if (collectors == null) {
            collectors = Lists.newArrayList();
            scopeCollectors.put(scope, collectors);
        }
        collectors.add(collector);
    }

    public List<Collector> removeCollectors(String scope) {
        if (scopeCollectors == null) {
            return null;
        }
        return scopeCollectors.remove(scope);
    }

    public boolean hasCollectors(String scope) {
        if (scopeCollectors == null) {
            return false;
        }
        if (!scopeCollectors.containsKey(scope)) {
            return false;
        }
        return !scopeCollectors.get(scope).isEmpty();
    }

    public void processingScope(String scope) {
        this.processingScope = scope;
    }

    public void processedScope() {
        // clean the current scope (we processed it, also handles scrolling since we don't want to
        // do it again)
        if (scopeCollectors != null) {
            scopeCollectors.remove(processingScope);
        }
        this.processingScope = Scopes.NA;
    }

    @Override public Query rewrite(Query original) throws IOException {
        if (original == searchContext.query() || original == searchContext.parsedQuery().query()) {
            // optimize in case its the top level search query and we already rewrote it...
            if (searchContext.queryRewritten()) {
                return searchContext.query();
            }
            Query rewriteQuery = super.rewrite(original);
            searchContext.updateRewriteQuery(rewriteQuery);
            return rewriteQuery;
        } else {
            return super.rewrite(original);
        }
    }

    @Override public Weight createNormalizedWeight(Query query) throws IOException {
        // if its the main query, use we have dfs data, only then do it
        if (dfSource != null && (query == searchContext.query() || query == searchContext.parsedQuery().query())) {
            return dfSource.createNormalizedWeight(query);
        }
        return super.createNormalizedWeight(query);
    }

    // override from the Searcher to allow to control if scores will be tracked or not
    // LUCENE MONITOR - We override the logic here to apply our own flags for track scores
    @Override public TopFieldDocs search(Weight weight, Filter filter, int nDocs,
                                         Sort sort, boolean fillFields) throws IOException {
        int limit = reader.maxDoc();
        if (limit == 0) {
            limit = 1;
        }
        nDocs = Math.min(nDocs, limit);

        TopFieldCollector collector = TopFieldCollector.create(sort, nDocs,
                fillFields, searchContext.trackScores(), searchContext.trackScores(), !weight.scoresDocsOutOfOrder());
        search(weight, filter, collector);
        return (TopFieldDocs) collector.topDocs();
    }

    @Override public void search(Weight weight, Filter filter, Collector collector) throws IOException {
        if (searchContext.parsedFilter() != null && Scopes.MAIN.equals(processingScope)) {
            // this will only get applied to the actual search collector and not
            // to any scoped collectors, also, it will only be applied to the main collector
            // since that is where the filter should only work
            collector = new FilteredCollector(collector, searchContext.parsedFilter());
        }
        if (searchContext.timeout() != null) {
            // TODO: change to use our own counter that uses the scheduler in ThreadPool
            collector = new TimeLimitingCollector(collector, TimeLimitingCollector.getGlobalCounter(), searchContext.timeout().millis());
        }
        if (scopeCollectors != null) {
            List<Collector> collectors = scopeCollectors.get(processingScope);
            if (collectors != null && !collectors.isEmpty()) {
                collector = new MultiCollector(collector, collectors.toArray(new Collector[collectors.size()]));
            }
        }
        // apply the minimum score after multi collector so we filter facets as well
        if (searchContext.minimumScore() != null) {
            collector = new MinimumScoreCollector(collector, searchContext.minimumScore());
        }

        Filter combinedFilter;
        if (filter == null) {
            combinedFilter = searchContext.aliasFilter();
        } else {
            if (searchContext.aliasFilter() != null) {
                combinedFilter = new AndFilter(ImmutableList.of(filter, searchContext.aliasFilter()));
            } else {
                combinedFilter = filter;
            }
        }

        // we only compute the doc id set once since within a context, we execute the same query always...
        if (searchContext.timeout() != null) {
            searchContext.queryResult().searchTimedOut(false);
            try {
                super.search(weight, combinedFilter, collector);
            } catch (TimeLimitingCollector.TimeExceededException e) {
                searchContext.queryResult().searchTimedOut(true);
            }
        } else {
            super.search(weight, combinedFilter, collector);
        }
    }
}