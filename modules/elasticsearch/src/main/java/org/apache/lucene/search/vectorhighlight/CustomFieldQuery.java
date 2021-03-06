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

package org.apache.lucene.search.vectorhighlight;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.MultiTermQueryWrapperFilter;
import org.apache.lucene.search.PublicTermsFilter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.elasticsearch.common.lucene.search.MultiPhrasePrefixQuery;
import org.elasticsearch.common.lucene.search.TermFilter;
import org.elasticsearch.common.lucene.search.XBooleanFilter;
import org.elasticsearch.common.lucene.search.function.FiltersFunctionScoreQuery;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;

/**
 * @author kimchy (shay.banon)
 */
// LUCENE MONITOR
public class CustomFieldQuery extends FieldQuery {

    private static Field multiTermQueryWrapperFilterQueryField;

    static {
        try {
            multiTermQueryWrapperFilterQueryField = MultiTermQueryWrapperFilter.class.getDeclaredField("query");
            multiTermQueryWrapperFilterQueryField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // ignore
        }
    }

    public static final ThreadLocal<Boolean> highlightFilters = new ThreadLocal<Boolean>();

    public CustomFieldQuery(Query query, IndexReader reader, FastVectorHighlighter highlighter) throws IOException {
        this(query, reader, highlighter.isPhraseHighlight(), highlighter.isFieldMatch());
    }

    public CustomFieldQuery(Query query, IndexReader reader, boolean phraseHighlight, boolean fieldMatch) throws IOException {
        super(query, reader, phraseHighlight, fieldMatch);
        highlightFilters.remove();
    }

    @Override void flatten(Query sourceQuery, IndexReader reader, Collection<Query> flatQueries) throws IOException {
        if (sourceQuery instanceof DisjunctionMaxQuery) {
            DisjunctionMaxQuery dmq = (DisjunctionMaxQuery) sourceQuery;
            for (Query query : dmq) {
                flatten(query, reader, flatQueries);
            }
        } else if (sourceQuery instanceof SpanTermQuery) {
            TermQuery termQuery = new TermQuery(((SpanTermQuery) sourceQuery).getTerm());
            if (!flatQueries.contains(termQuery)) {
                flatQueries.add(termQuery);
            }
        } else if (sourceQuery instanceof ConstantScoreQuery) {
            ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) sourceQuery;
            if (constantScoreQuery.getFilter() != null) {
                flatten(constantScoreQuery.getFilter(), reader, flatQueries);
            } else {
                flatten(constantScoreQuery.getQuery(), reader, flatQueries);
            }
        } else if (sourceQuery instanceof FunctionScoreQuery) {
            flatten(((FunctionScoreQuery) sourceQuery).getSubQuery(), reader, flatQueries);
        } else if (sourceQuery instanceof FilteredQuery) {
            flatten(((FilteredQuery) sourceQuery).getQuery(), reader, flatQueries);
            flatten(((FilteredQuery) sourceQuery).getFilter(), reader, flatQueries);
        } else if (sourceQuery instanceof MultiPhrasePrefixQuery) {
            try {
                flatten(sourceQuery.rewrite(reader), reader, flatQueries);
            } catch (IOException e) {
                // ignore
            }
        } else if (sourceQuery instanceof FiltersFunctionScoreQuery) {
            flatten(((FiltersFunctionScoreQuery) sourceQuery).getSubQuery(), reader, flatQueries);
        } else {
            super.flatten(sourceQuery, reader, flatQueries);
        }
    }

    void flatten(Filter sourceFilter, IndexReader reader, Collection<Query> flatQueries) throws IOException {
        Boolean highlight = highlightFilters.get();
        if (highlight == null || highlight.equals(Boolean.FALSE)) {
            return;
        }
        if (sourceFilter instanceof TermFilter) {
            flatten(new TermQuery(((TermFilter) sourceFilter).getTerm()), reader, flatQueries);
        } else if (sourceFilter instanceof PublicTermsFilter) {
            PublicTermsFilter termsFilter = (PublicTermsFilter) sourceFilter;
            for (Term term : termsFilter.getTerms()) {
                flatten(new TermQuery(term), reader, flatQueries);
            }
        } else if (sourceFilter instanceof MultiTermQueryWrapperFilter) {
            if (multiTermQueryWrapperFilterQueryField != null) {
                try {
                    flatten((Query) multiTermQueryWrapperFilterQueryField.get(sourceFilter), reader, flatQueries);
                } catch (IllegalAccessException e) {
                    // ignore
                }
            }
        } else if (sourceFilter instanceof XBooleanFilter) {
            XBooleanFilter booleanFilter = (XBooleanFilter) sourceFilter;
            if (booleanFilter.getMustFilters() != null) {
                for (Filter filter : booleanFilter.getMustFilters()) {
                    flatten(filter, reader, flatQueries);
                }
            }
            if (booleanFilter.getShouldFilters() != null) {
                for (Filter filter : booleanFilter.getShouldFilters()) {
                    flatten(filter, reader, flatQueries);
                }
            }
        }
    }
}
