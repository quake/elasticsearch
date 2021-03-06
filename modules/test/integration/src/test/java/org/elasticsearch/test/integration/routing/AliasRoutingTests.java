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

package org.elasticsearch.test.integration.routing;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.RoutingMissingException;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.elasticsearch.cluster.metadata.AliasAction.newAddAliasAction;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author imotov
 */
public class AliasRoutingTests extends AbstractNodesTests {

    private Client client;

    @BeforeClass public void createNodes() throws Exception {
        startNode("node1");
        startNode("node2");
        client = getClient();
    }

    @AfterClass public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    protected Client getClient() {
        return client("node1");
    }

    @Test public void testAliasCrudRouting() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.admin().indices().prepareAliases().addAliasAction(newAddAliasAction("test", "alias0").routing("0")).execute().actionGet();

        logger.info("--> indexing with id [1], and routing [0] using alias");
        client.prepareIndex("alias0", "type1", "1").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }
        logger.info("--> verifying get with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> verifying get with routing alias, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> deleting with no routing, should not delete anything");
        client.prepareDelete("test", "type1", "1").setRefresh(true).execute().actionGet();
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(true));
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> deleting with routing alias, should delete");
        client.prepareDelete("alias0", "type1", "1").setRefresh(true).execute().actionGet();
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }

        logger.info("--> indexing with id [1], and routing [0] using alias");
        client.prepareIndex("alias0", "type1", "1").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }
        logger.info("--> verifying get with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(true));
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> deleting_by_query with 1 as routing, should not delete anything");
        client.prepareDeleteByQuery().setQuery(matchAllQuery()).setRouting("1").execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(true));
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> deleting_by_query with alias0, should delete");
        client.prepareDeleteByQuery("alias0").setQuery(matchAllQuery()).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }
    }

    @Test public void testAliasSearchRouting() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.admin().indices().prepareAliases()
                .addAliasAction(newAddAliasAction("test", "alias"))
                .addAliasAction(newAddAliasAction("test", "alias0").routing("0"))
                .addAliasAction(newAddAliasAction("test", "alias1").routing("1"))
                .addAliasAction(newAddAliasAction("test", "alias01").searchRouting("0,1"))
                .execute().actionGet();


        logger.info("--> indexing with id [1], and routing [0] using alias");
        client.prepareIndex("alias0", "type1", "1").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }
        logger.info("--> verifying get with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("alias0", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> search with no routing, should fine one");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
        }

        logger.info("--> search with wrong routing, should not find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setRouting("1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(0l));
            assertThat(client.prepareCount().setRouting("1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(0l));
            assertThat(client.prepareSearch("alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(0l));
            assertThat(client.prepareCount("alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(0l));
        }

        logger.info("--> search with correct routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setRouting("0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount().setRouting("0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
            assertThat(client.prepareSearch("alias0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount("alias0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
        }

        logger.info("--> indexing with id [2], and routing [1] using alias");
        client.prepareIndex("alias1", "type1", "2").setSource("field", "value1").setRefresh(true).execute().actionGet();

        logger.info("--> search with no routing, should fine two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount().setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

        logger.info("--> search with 0 routing, should find one");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setRouting("0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount().setRouting("0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
            assertThat(client.prepareSearch("alias0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount("alias0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
        }

        logger.info("--> search with 1 routing, should find one");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setRouting("1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount().setRouting("1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
            assertThat(client.prepareSearch("alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount("alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
        }

        logger.info("--> search with 0,1 routings , should find two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch().setRouting("0", "1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount().setRouting("0", "1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
            assertThat(client.prepareSearch("alias01").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("alias01").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

        logger.info("--> search with two routing aliases , should find two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("alias0", "alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("alias0", "alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

        logger.info("--> search with alias0, alias1 and alias01, should find two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("alias0", "alias1", "alias01").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("alias0", "alias1", "alias01").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

        logger.info("--> search with test, alias0 and alias1, should find two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("test", "alias0", "alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("test", "alias0", "alias1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

    }

    @Test public void testAliasSearchRoutingWithTwoIndices() throws Exception {
        try {
            client.admin().indices().prepareDelete("test-a").execute().actionGet();
            client.admin().indices().prepareDelete("test-b").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test-a").execute().actionGet();
        client.admin().indices().prepareCreate("test-b").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.admin().indices().prepareAliases()
                .addAliasAction(newAddAliasAction("test-a", "alias-a0").routing("0"))
                .addAliasAction(newAddAliasAction("test-a", "alias-a1").routing("1"))
                .addAliasAction(newAddAliasAction("test-b", "alias-b0").routing("0"))
                .addAliasAction(newAddAliasAction("test-b", "alias-b1").routing("1"))

                .addAliasAction(newAddAliasAction("test-a", "alias-ab").searchRouting("0"))
                .addAliasAction(newAddAliasAction("test-b", "alias-ab").searchRouting("1"))
                .execute().actionGet();

        logger.info("--> indexing with id [1], and routing [0] using alias to test-a");
        client.prepareIndex("alias-a0", "type1", "1").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }
        logger.info("--> verifying get with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("alias-a0", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> indexing with id [0], and routing [1] using alias to test-b");
        client.prepareIndex("alias-b1", "type1", "1").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
        }
        logger.info("--> verifying get with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("alias-b1", "type1", "1").execute().actionGet().exists(), equalTo(true));
        }


        logger.info("--> search with alias-a1,alias-b0, should not find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("alias-a1", "alias-b0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(0l));
            assertThat(client.prepareCount("alias-a1", "alias-b0").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(0l));
        }

        logger.info("--> search with alias-ab, should find two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("alias-ab").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("alias-ab").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

        logger.info("--> search with alias-a0,alias-b1 should find two");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("alias-a0", "alias-b1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("alias-a0", "alias-b1").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }
    }

    @Test public void testRequiredRoutingMappingWithAlias() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test")
                .addMapping("type1", XContentFactory.jsonBuilder().startObject().startObject("type1").startObject("_routing").field("required", true).endObject().endObject().endObject())
                .execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        logger.info("--> indexing with id [1], and routing [0]");
        client.prepareIndex("test", "type1", "1").setRouting("0").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");

        logger.info("--> indexing with id [1], with no routing, should fail");
        try {
            client.prepareIndex("test", "type1", "1").setSource("field", "value1").setRefresh(true).execute().actionGet();
            assert false;
        } catch (ElasticSearchException e) {
            assertThat(e.unwrapCause(), instanceOf(RoutingMissingException.class));
        }

        logger.info("--> verifying get with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(true));
        }

        logger.info("--> deleting with no routing, should broadcast the delete since _routing is required");
        client.prepareDelete("test", "type1", "1").setRefresh(true).execute().actionGet();
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(false));
        }

        logger.info("--> indexing with id [1], and routing [0]");
        client.prepareIndex("test", "type1", "1").setRouting("0").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");

        logger.info("--> bulk deleting with no routing, should broadcast the delete since _routing is required");
        client.prepareBulk().add(Requests.deleteRequest("test").type("type1").id("1")).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "1").execute().actionGet().exists(), equalTo(false));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("0").execute().actionGet().exists(), equalTo(false));
        }
    }

    @Test public void testIndexingAliasesOverTime() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();


        logger.info("--> creating alias with routing [3]");
        client.admin().indices().prepareAliases()
                .addAliasAction(newAddAliasAction("test", "alias").routing("3"))
                .execute().actionGet();

        logger.info("--> indexing with id [0], and routing [3]");
        client.prepareIndex("alias", "type1", "0").setSource("field", "value1").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");

        logger.info("--> verifying get and search with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "0").setRouting("3").execute().actionGet().exists(), equalTo(true));
            assertThat(client.prepareSearch("alias").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(1l));
            assertThat(client.prepareCount("alias").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(1l));
        }

        logger.info("--> creating alias with routing [4]");
        client.admin().indices().prepareAliases()
                .addAliasAction(newAddAliasAction("test", "alias").routing("4"))
                .execute().actionGet();

        logger.info("--> verifying search with wrong routing should not find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareSearch("alias").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(0l));
            assertThat(client.prepareCount("alias").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(0l));
        }

        logger.info("--> creating alias with search routing [3,4] and index routing 4");
        client.admin().indices().prepareAliases()
                .addAliasAction(newAddAliasAction("test", "alias").searchRouting("3,4").indexRouting("4"))
                .execute().actionGet();

        logger.info("--> indexing with id [1], and routing [4]");
        client.prepareIndex("alias", "type1", "1").setSource("field", "value2").setRefresh(true).execute().actionGet();
        logger.info("--> verifying get with no routing, should not find anything");

        logger.info("--> verifying get and search with routing, should find");
        for (int i = 0; i < 5; i++) {
            assertThat(client.prepareGet("test", "type1", "0").setRouting("3").execute().actionGet().exists(), equalTo(true));
            assertThat(client.prepareGet("test", "type1", "1").setRouting("4").execute().actionGet().exists(), equalTo(true));
            assertThat(client.prepareSearch("alias").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().hits().totalHits(), equalTo(2l));
            assertThat(client.prepareCount("alias").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet().count(), equalTo(2l));
        }

    }

}
