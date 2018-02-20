package com.zimbra.qa.unittest;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient.RemoteSolrException;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest.ACTION;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.zimbra.common.httpclient.ZimbraHttpClientManager;
import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.event.SolrEventStore;
import com.zimbra.cs.event.StandaloneSolrEventStore;
import com.zimbra.cs.event.analytics.contact.ContactAnalytics;
import com.zimbra.cs.event.analytics.contact.ContactAnalytics.ContactFrequencyGraphSpec;
import com.zimbra.cs.event.logger.SolrEventCallback;
import com.zimbra.cs.index.solr.AccountCollectionLocator;
import com.zimbra.cs.index.solr.JointCollectionLocator;
import com.zimbra.cs.index.solr.SolrCollectionLocator;
import com.zimbra.cs.index.solr.SolrIndex.IndexType;
import com.zimbra.cs.index.solr.SolrRequestHelper;
import com.zimbra.cs.index.solr.SolrUtils;
import com.zimbra.cs.index.solr.StandaloneSolrHelper;

public class TestStandaloneSolrEventStore extends SolrEventStoreTestBase {

    private static String CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_USERNAME = "contactFrequencyGraphTestAccount";
    private static Account CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT;

    private static String baseUrl;
    private static CloseableHttpClient httpClient;

    @BeforeClass
    public static void init() throws Exception {
        String solrUrl = Provisioning.getInstance().getLocalServer().getEventBackendURL();
        Assume.assumeTrue(solrUrl.startsWith("solr"));
        baseUrl = solrUrl.substring("solr:".length());
        httpClient = ZimbraHttpClientManager.getInstance().getInternalHttpClient();
        TestUtil.deleteAccountIfExists(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_USERNAME);
        TestUtil.deleteAccountIfExists(ACCOUNT_1);
        TestUtil.deleteAccountIfExists(ACCOUNT_2);
        ACCOUNT_ID_1 = TestUtil.createAccount(ACCOUNT_1).getId();
        ACCOUNT_ID_2 = TestUtil.createAccount(ACCOUNT_2).getId();
        CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT = TestUtil.createAccount(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_USERNAME);
        CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_ID = CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT.getId();
        cleanUp();
    }

    private CloseableHttpClient getHttpClient() {
        return ZimbraHttpClientManager.getInstance().getInternalHttpClient();
    }

    private static SolrClient getSolrClient(String coreName) {
        return SolrUtils.getSolrClient(httpClient, baseUrl, coreName);
    }

    private static void deleteCore(String core) throws Exception {
        SolrClient client = getSolrClient(core);
        try{
            SolrUtils.deleteStandaloneIndex(client, baseUrl, core);
        } catch (RemoteSolrException | ServiceException e) {}
    }

    public static void cleanUp() throws Exception {
        deleteCore(JOINT_COLLECTION_NAME);
        deleteCore(getAccountCollectionName(ACCOUNT_ID_1));
        deleteCore(getAccountCollectionName(ACCOUNT_ID_2));
        deleteCore(getAccountCollectionName(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_ID));
    }

    @After
    public void tearDown() throws Exception {
        cleanUp();
    }

    @AfterClass
    public static void clean() throws Exception {
        TestUtil.deleteAccountIfExists(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_USERNAME);
        TestUtil.deleteAccountIfExists(ACCOUNT_1);
        TestUtil.deleteAccountIfExists(ACCOUNT_2);
    }

    @Override
    protected StandaloneSolrEventStore getCombinedEventStore(String accountId) {
        SolrCollectionLocator locator = new JointCollectionLocator(JOINT_COLLECTION_NAME);
        StandaloneSolrHelper helper = new StandaloneSolrHelper(locator, getHttpClient(), IndexType.EVENTS, baseUrl);
        return new StandaloneSolrEventStore(accountId, helper);
    }

    @Override
    protected StandaloneSolrEventStore getAccountEventStore(String accountId) {
        SolrCollectionLocator locator = new AccountCollectionLocator(JOINT_COLLECTION_NAME);
        StandaloneSolrHelper helper = new StandaloneSolrHelper(locator, getHttpClient(), IndexType.EVENTS, baseUrl);
        return new StandaloneSolrEventStore(accountId, helper);
    }

    @Override
    protected void commit(String coreName) throws Exception {
        UpdateRequest commitReq = new UpdateRequest();
        SolrClient client = getSolrClient(coreName);
        commitReq.setAction(ACTION.COMMIT, true, true);
        commitReq.process(client);
    }

    @Override
    protected SolrEventCallback getCombinedCoreCallback() {
        SolrCollectionLocator locator = new JointCollectionLocator(JOINT_COLLECTION_NAME);
        CloseableHttpClient httpClient = ZimbraHttpClientManager.getInstance().getInternalHttpClient();
        SolrRequestHelper requestHelper = new StandaloneSolrHelper(locator, httpClient, IndexType.EVENTS, baseUrl);
        return new SolrEventCallback(requestHelper);
    }

    @Override
    protected SolrEventCallback getAccountCoreCallback() {
        SolrCollectionLocator locator = new AccountCollectionLocator(JOINT_COLLECTION_NAME);
        CloseableHttpClient httpClient = ZimbraHttpClientManager.getInstance().getInternalHttpClient();
        SolrRequestHelper requestHelper = new StandaloneSolrHelper(locator, httpClient, IndexType.EVENTS, baseUrl);
        return new SolrEventCallback(requestHelper);
    }

    @Override
    protected SolrQuery newQuery(String coreName) {
        return new SolrQuery();
    }

    @Override
    protected SolrDocumentList executeRequest(String coreName,
            QueryRequest req) throws Exception {
        SolrClient client = getSolrClient(coreName);
        QueryResponse resp = req.process(client);
        return resp.getResults();
    }

    @Test
    public void testContactFrequencyCountForAllTimeRanges() throws Exception {
        for (ContactAnalytics.ContactFrequencyTimeRange timeRange : getContactFrequencyCountTimeRanges()) {
            testContactFrequencyCountForAccountCore(timeRange);
            testContactFrequencyCountForCombinedCore(timeRange);
        }
    }

    public void testContactFrequencyCountForAccountCore(ContactAnalytics.ContactFrequencyTimeRange timeRange) throws Exception {
        cleanUp();
        try(SolrEventCallback eventCallback = getAccountCoreCallback()) {
            testContactFrequencyCount(timeRange, eventCallback, getAccountCollectionName(ACCOUNT_ID_1), getAccountEventStore(ACCOUNT_ID_1));
        }
    }

    public void testContactFrequencyCountForCombinedCore(ContactAnalytics.ContactFrequencyTimeRange timeRange) throws Exception {
        cleanUp();
        try(SolrEventCallback eventCallback = getCombinedCoreCallback()) {
            testContactFrequencyCount(timeRange, eventCallback, JOINT_COLLECTION_NAME, getCombinedEventStore(ACCOUNT_ID_1));
        }
    }

    @Test
    public void testGetContactFrequencyGraphForAllTimeRanges() throws Exception {
        for (ContactFrequencyGraphSpec graphSpec: getContactFrequencyGraphSpecs()) {
            testContactFrequencyGraphForAccountCore(graphSpec);
            testContactFrequencyGraphForCombinedCore(graphSpec);
        }
    }

    private void testContactFrequencyGraphForAccountCore(ContactFrequencyGraphSpec graphSpec) throws Exception {
        cleanUp();
        try(SolrEventCallback eventCallback = getAccountCoreCallback()) {
            String collectionName = getAccountCollectionName(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_ID);
            SolrEventStore eventStore = getAccountEventStore(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_ID);
            testContactFrequencyGraph(graphSpec, eventCallback, collectionName, eventStore);
        }
    }

    private void testContactFrequencyGraphForCombinedCore(ContactFrequencyGraphSpec graphSpec) throws Exception {
        cleanUp();
        try(SolrEventCallback eventCallback = getCombinedCoreCallback()) {
            SolrEventStore eventStore = getCombinedEventStore(CONTACT_FREQUENCY_GRAPH_TEST_ACCOUNT_ID);
            testContactFrequencyGraph(graphSpec, eventCallback, JOINT_COLLECTION_NAME, eventStore);
        }
    }

    @Test
    public void testPercentageOpenedEmails() throws Exception {
        testPercentageOpenedEmailsForAccountCore();
        testPercentageOpenedEmailsForCombinedCore();
    }

    public void testPercentageOpenedEmailsForAccountCore() throws Exception {
        cleanUp();
        try(SolrEventCallback eventCallback = getAccountCoreCallback()) {
            testPercentageOpenedEmails(eventCallback, getAccountCollectionName(ACCOUNT_ID_1), getAccountEventStore(ACCOUNT_ID_1));
        }
    }

    public void testPercentageOpenedEmailsForCombinedCore() throws Exception {
        cleanUp();
        try(SolrEventCallback eventCallback = getCombinedCoreCallback()) {
            testPercentageOpenedEmails(eventCallback, JOINT_COLLECTION_NAME, getCombinedEventStore(ACCOUNT_ID_1));
        }
    }
}
